SIM ?= verilator
TOPLEVEL_LANG = verilog
WAVES = 1
EXTRA_ARGS += --trace --trace-structs

HWMODULE = Axi4LiteMac
SBT_MAIN = axi_wrapped.Axi4LiteMacMain
SRCDIR = $(abspath ../../../generated/$(HWMODULE))
DUT_SV = $(SRCDIR)/$(HWMODULE).sv

VERILOG_SOURCES = $(SRCDIR)/user_accel_bd_wrapper.v $(SRCDIR)/Mac.sv $(DUT_SV)
COCOTB_TOPLEVEL = user_accel_bd_wrapper
COCOTB_TEST_MODULES = Axi4LiteMacSim

# COCOTB_Bridge.readParams() reads $PARAMFN; moduleName="Mac" -> Mac_params.json
export PARAMFN = $(SRCDIR)/Mac_params.json

# elaborate Chisel design into SV
$(DUT_SV):
	cd ../../.. && sbt "runMain $(SBT_MAIN)"

# force regeneration
.PHONY: gen
gen:
	rm -f $(DUT_SV)
	$(MAKE) $(DUT_SV)

# ensure verilog exists before sim
sim: $(DUT_SV)

include $(shell cocotb-config --makefiles)/Makefile.sim

clean::
	rm -rf __pycache__ results.xml sim_build *.vcd *.fst output.log
