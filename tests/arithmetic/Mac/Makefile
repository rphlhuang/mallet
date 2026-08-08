SIM ?= icarus
TOPLEVEL_LANG = verilog
WAVES = 1

HWMODULE = Mac
SBT_MAIN = arithmetic.MacMain
SRCDIR = $(abspath ../../../generated/arithmetic)
DUT_SV = $(SRCDIR)/$(HWMODULE).sv
VERILOG_SOURCES = $(DUT_SV)
COCOTB_TOPLEVEL = $(HWMODULE)
COCOTB_TEST_MODULES = MacSim

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
	rm -rf __pycache__ results.xml sim_build *.vcd *.fst