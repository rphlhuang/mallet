SIM ?= verilator
TOPLEVEL_LANG = verilog
WAVES = 1
EXTRA_ARGS += --trace --trace-structs

HWMODULE = Axi4LiteFPU
SBT_MAIN = axi_wrapped.Axi4LiteFPUMain
SRCDIR = $(abspath ../../../generated/$(HWMODULE))
DUT_SV = $(SRCDIR)/$(HWMODULE).sv

VERILOG_SOURCES = $(SRCDIR)/user_accel_bd_wrapper.v $(addprefix $(SRCDIR)/,$(shell cat $(SRCDIR)/filelist.f))
COCOTB_TOPLEVEL = user_accel_bd_wrapper
COCOTB_TEST_MODULES = Axi4LiteFPUSim

# COCOTB_Bridge.readParams() reads $PARAMFN; moduleName="FPU" -> FPU_params.json
export PARAMFN = $(SRCDIR)/FPU_params.json

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