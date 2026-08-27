# mallet

## Overview
`mallet` is a fully open-source formal verification harness for the Chisel stack that issues correctness properties derived from AXI memory map annotations to multiple independent model-checking engines, producing an adjudication matrix to support agile verification of scientific-computing accelerators. 
Since properties should be rendered from the design, io/spec/property drift is caught compile-time, crucial for building agentic loops.
Annotations are "black box" by construction: every argument a memory-map annotation takes is an address, a literal, or an access mode - notably not a DUT internal signal. A spec therefore names only what a bus master could observe, and persists throughout refactoring of the design it describes.
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

### Basics: Three Property Tiers

Every `mallet` property travels through the flow tagged with its origin, the *tier* that produced it, so the adjudication matrix can be grouped and counted by it.

| Tier | Written by | Knows about | Surface |
| ---- | ---------- | ----------- | ------- |
| `transport` | the protocol contract | the bus type only | `S.AXI conformsTo AxiLite32Slave` |
| `memmap` | generated from your annotations | addresses and access modes | `p.status_r is Status setBy p.push_w` |
| `manual` | you, by hand or approving LLM changes | anything, including internal signals | `property(...) { ... }` / `assume(...) { ... }` |

Note here that `manual` is the only tier permitted to reference a signal inside the design. `transport` and `memmap` are black box, so a property from either tier can only observe what a bus master can see. When a design fact needs an internal signal, it goes in `manual`, benefiting from `mallet`'s better lowering capabitilies over Chisel's Assert/AssumeProperty()s.

### Basics: Annotating the Memory Map

With `mallet`, many manually-written formal properties can be automatically generated from *design annotations* instead, which automatically generate syntactically correct properties proven to lower down to the formal engines (many common SVA properties like |=> cannot be parsed by open-source formal tools). A `mallet` spec is a subclass of your DUT that mixes in `MalletSpec`, and it reads like the comments you'd already put on a memory map:

```scala
class MacSpec(p: MacModuleParams) extends Axi4LiteMac(p) with MalletSpec {
  p.a_w           is WO
  p.b_w           is WO
  p.push_w        is Commit requiring (p.a_w, p.b_w)
  p.status_r      is Status  setBy   p.push_w
  p.result_r      is Result  gatedBy p.status_r
  p.soft_reset_rw is WO

  S.AXI conformsTo AxiLite32Slave

  property("soft_reset_is_pulse") { srPulse |=> !srPulse }
  property("result_not_dropped")  { dut.io.out.fire |=> dutValidReg }

  done()
}
```

Since the address is the main character, the left column reads top-to-bottom as the memory map itself. Subclassing the DUT is what lets the `property(...)` and `assume(...)` reach internal DUT signals. Each line adds its properties and corresponding registers to a queue, and `done()` flushes them all at the end.

#### Access modes

Access modes in `mallet` are inspired by fairly standard register-access taxonomy, which [SystemRDL 2.0](https://www.accellera.org/images/downloads/standards/systemrdl/SystemRDL_2.0_Jan2018.pdf) and [UVM 1.2 RAL](https://verificationacademy.com/verification-methodology-reference/uvm/docs_1.1a/html/files/reg/uvm_reg_field-svh.html) implement.

| Mode | Meaning | Emits... |
| ---- | ------- | -------- |
| `RO` | reads succeed, writes refused | `mm_write_errs_A`, `mm_read_ok_A` |
| `WO` | writes succeed, reads refused | `mm_read_errs_A` |
| `RW` | readable and writable | `mm_read_ok_A` |
| `Storage` | software-owned storage: a read returns the last value written (good for on-the-fly params) | `mm_read_ok_A`, `mm_readback_A` |
| `RC` | read-only *and* destructive (e.g. the read pops) | `mm_write_errs_A` |
| `W1C` | write-one-to-clear | access permissions only |

Note that, for now, only **negative** obligations are asserted: a write here must be refused, a read here must be refused. Positive obligations are liveness properties (not currently supported in FOSS), so `mallet` never asserts that a write succeeds, for example.

This gives us plenty of consequences; here we detail two examples. `RC` suppresses readback rather than adding a property, since declaring a read destructive is what makes idempotence unassertable. And `W1C`'s clearing behaviour is not black-box-observable at all, since hardware may re-set the bit between your write and your read, so `W1C` only contributes permissions; prove the clearing in the manual tier if a design needs it.

Beyond the declared addresses, the map as a whole emits `mm_unmapped_read` and `mm_unmapped_write` over the complement of the declared set: anything not in the map must be refused with SLVERR.

#### Roles

A role is a named, recurring pairing of an access mode with a relation which takes another address in the same map.

| Role | Mode | Relation | Adds... |
| ---- | ---- | -------- | ------- |
| `Status` | `RO` | `setBy <commitAddr>` | `mm_solicited_A`: the address reports ready only if the commit address was actually written since reset |
| `Result` | `RC` | `gatedBy <statusAddr>` | `mm_gated_A`: reading the result while the last observed status read said not-ready must be refused |
| `Commit` | `WO` | `requiring (<operandAddrs>*)` | `mm_requires_A`, an **assume**: the master writes the commit address only after writing every operand |

Relations are optional, so `p.x is Status` alone gives you just the `RO` properties.

Black-box properties need *memory*, since a write's effect is invisible until a later read. `mallet` reconstructs that history from the interface alone, keeping per-address "was written since reset", "last value written" and "last value read" monitors built purely from AXI handshakes. These are still safety properties: the monitors are essentially simple state bits that makes a past-dependent assertion expressible, in the same way SVA's `$past` does.

#### Manual Properties

Manually written temporal logic a la Chisel3 use `property(name) { ... }` and `assume(name) { ... }`, which are `mallet`'s equivalents of Chisel's `AssertProperty()` / `AssumeProperty()`, with the added benefit of `|=>` support and automatic warm-up masking to prevent counterexamples before reset. Going through `mallet` rather than calling `chisel3.ltl` directly is also what gets a property its stable label, its English rendering, its reachability cover, and its row in the adjudication matrix.

This is the only tier that may reference internal signals.

### Basics: Adjudication

A `mallet` run queues up threads for each {property, backend engine} combination, and reports them in an *adjudication matrix*. After all combinations have executed or the timeout (default: 20s) is reached, the matrix is populated with the results from each backend. These results combine to compose a verdict for each property, which can take one of the following values:

- PROVEN   = An unbounded proof was found.
- NOCEX    = No counterexample was found within `kmax` cycles. Bounded, not a proof.
- REFUTED  = A counterexample exists, and all engines agree.
- CONFLICT = A counterexample exists, but one one enginer found a CEX another ruled out.
- VACUOUS  = Proves nothing, so was simplified away or the antecedent of the implication was unreachable.

In addition, assumptions (`AssumeProperty()` in Chisel) are labelled with ASSUMED.