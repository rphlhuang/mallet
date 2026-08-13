# mallet

## Overview
`mallet` is a fully open-source formal verification harness for the Chisel stack that issues correctness properties derived from AXI memory map annotations to multiple independent model-checking engines, producing an adjudication matrix to support agile verification of scientific-computing accelerators. 
Since properties should be rendered from the design, io/spec/property drift is caught compile-time, crucial for building agentic loops.
Inspired by [FLAG: Formal and LLM-assisted SVA Generation for Formal Specifications of On-Chip Communication Protocols](http://arxiv.org/abs/2504.17226), `mallet` takes a formal-first approach that centers around a custom grammar for all properties. Each `mallet` property has a 3 representations -- (natural language (English), Chisel assertion, abstract syntax tree) -- for three separate but cohesive purposes:

1) Natural language: for human and LLM interpretability. Studies show that [LLMs have poor temporal reasoning skills](http://arxiv.org/abs/2406.09170) so natural language improves the LLM's understanding of the assertion set. Natural language also helps the human engineer interpret the formal engine's (btormc's) verdict, as it becomes divorced from the original Chisel syntax as it is lowered down from Chisel assertions to btor2.
2) Chisel assertions: what gets written to the Chisel file, alongside the your Chisel code. By tying Chisel assertions to the other two representations, we prevent the LLM from generating invalid syntax and assertion types that are supported by chisel3.ltl but unsupported by CIRCT.
3) Abstract syntax tree (AST): for pre-SMT solving simplifications. By representing the property using `mallet`'s custom algebraic data types, we can SAT solve to remove trivial, vacuous, and contradictory properties from the set pre-btor2-lowering.

## Dependencies

Mallet currently runs on Chisel 7.13.0 and scalatest 3.2.19.

#### JDK 8 or newer

We recommend LTS releases Java 8 and Java 11. You can install the JDK as your operating system recommends, or use the prebuilt binaries from [AdoptOpenJDK](https://adoptopenjdk.net/).

#### SBT

SBT is the most common build tool in the Scala community. You can download it [here](https://www.scala-sbt.org/download.html)

#### OSS-CAD-Suite: Verilator, Icarus, btormc, rIC3, pono

Mallet and cocotb require Verilator to be installed. Icarus can optionally be used as an alternative sim for cocotb tests.
`btormc`, `rIC3` and `pono` are the currently supported model-checking engines.
All these can be installed via the oss-cad-suite, which has a nightly build release [here](https://github.com/YosysHQ/oss-cad-suite-build/releases), but Mallet was built and tested with the 20260708 release.

### Python libraries

> Note: cocotb 2.0.1 only supports a maximum Python version of 3.13.

Install all python libraries from `requirements.txt` using:
```bash
python3.13 -m venv venv
source venv/bin/activate
pip3.13 install -r requirements.txt
``` 

This will also install the `chisel-axi-bridge` python module as a package from `third_party/chisel-axi-utils`.


## Usage

| Target | Description |
| ------ | ----------- |
| `make mallet` | run whole Mallet flow; `KMAX=` sets the BMC bound (default 20), `TIMEOUT=` sets timeout (default 20s) |
| `make chiselsim` | run the Chisel scalatest suite (sbt test), `FORCE=1` forces all tests to run |
| `make cocotb` | run every cocotb testbench under tests/ |
| `make gen` | re-elaborate every Chisel App to SystemVerilog |
| `make clean` | clean generated SV, mallet results, cocotb sim outputs, sbt target |

## Docs

### Basics: `mallet`'s Algebraic Data Types (ADTs)

`mallet` properties have three stacked levels of ADTs. All `properties` are made of `boolean` expressions, and all `boolean` expressions consist of the composition of `word` types.

| Level | Type | Cases | Represents... |
| ----- | ---- | ----- | ------------- |
| word | `Term` | Sig, Slice, Lit | a bus value: a signal, a bit-slice, a constant |
| boolean | `Expr` | B, Not, And, Or, Cmp, Past, True/False | a 1-bit condition |
| properties | `Prop` | Implies, Always | a whole assertion |

`Term` and `Expr` are explicitly different for type safety; `Not(Sig(awaddr))` on a 32-bit AXI-Lite bus wouldn't make sense and causes compile errors.

### Basics: Protocol Contracts/Annotations

To facilitate formal verification for a multiplicity of common communication protocols, `mallet` ships protocol contracts (see `src/main/scala/mallet/contract`) to automatically verify Chisel modules that inherit from certain constrained interfaces. Currently the only interface supported is 32-bit AMBA AXI-Lite, which requires your module under test to extend `axi.HasAxiLite32IO` from the `chisel-axi-utils` submodule. To activate protocol contracts, use the `conformsTo` function on the AxiLite32IO bus, e.g. `S.AXI conformsTo AxiLite32Slave` with infix notation.

### Basics: Annotating the Memory Map

With `mallet`, many manually-written formal properties can be automatically generated from *design annotations* instead, which automatically generate syntactically correct properties proven to lower down to the formal engines (many common SVA properties like |=> cannot be parsed by open-source formal tools). A `mallet` spec is a subclass of your DUT (so every internal signal is already in scope) that mixes in `MalletSpec`, and it reads like the comments you'd already put on a memory map:

```scala
class MacSpec(p: MacModuleParams) extends Axi4LiteMac(p) with MalletSpec {
  p.a_w           is Operand at aReg
  p.b_w           is Operand at bReg
  p.push_w        is Commit  at pushPendingReg requiring (aReg, bReg) acceptedOn dut.io.in.ready
  p.status_r      is Status  at dutValidReg
  p.result_r      is Result  at dutDataReg validWhen dutValidReg
  p.soft_reset_rw is RW

  S.AXI conformsTo AxiLite32Slave

  property("soft_reset_is_pulse") { srPulse |=> !srPulse }
  property("result_not_dropped")  { dut.io.out.fire |=> dutValidReg }

  done()
}
```

The address is the subject of every line, so the left column reads top-to-bottom as the memory map itself. Each line adds its properties and corresponding registers to a queue, and `done()` flushes them all at the end.

A role is a memory-map access mode plus a meaning. The **access modes** are `RO`, `WO`, `RW`, `W1C`. A **role** refines one of these and emits properties automatically:

| Role | Implies | Subject | Emits... |
| ---- | ------- | ------- | -------- |
| `Operand` | WO | operand register | declares the operand (consumed by `requiring`) |
| `Commit`  | W  | pending flag | pending retires once accepted; every `requiring` operand was written since reset |
| `Status`  | RO | status bit | a read of the address returns the bit |
| `Result`  | RO | data register | a read returns the data; reading clears the valid flag |

Note that an address can also carry a bare access mode with no role (`p.soft_reset_rw is RW`), which just documentation (for now).

Manually written temporal logic (a la Chisel `AssertProperty()`) can be written via `property(name) { ... }` with the added benefit of `|=>` support, automatic warm-up masking to prevent counterexamples before reset, and the fragment guards for free.

### Basics: Adjudication

A `mallet` run queues up threads for each {property, backend engine} combination, and reports them in an *adjudication matrix*. After all combinations have executed or the timeout (default: 20s) is reached, the matrix is populated with the results from each backend. These results combine to compose a verdict for each property, which can take one of the following values:

- PROVEN   = An unbounded proof was found.
- NOCEX    = No counterexample was found within `kmax` cycles. Bounded, not a proof.
- REFUTED  = A counterexample exists, and all engines agree.
- CONFLICT = A counterexample exists, but one one enginer found a CEX another ruled out.
- VACUOUS  = Proves nothing, so was simplified away or the antecedent of the implication was unreachable.

In addition, assumptions (`AssumeProperty()` in Chisel) are labelled with ASSUMED.
