# auto-discover every cocotb testbench dir: tests/<package>/<Module>/Makefile
COCOTB_DIRS := $(patsubst %/Makefile,%,$(shell find tests -name Makefile))

SBT_MAINS := $(shell grep -rlE '^[[:space:]]*object [A-Za-z0-9_]+Main extends App' src/main/scala | while read -r f; do \
	pkg=$$(grep -m1 -E '^package ' "$$f" | sed -E 's/^package[[:space:]]+//'); \
	grep -oE '^[[:space:]]*object [A-Za-z0-9_]+Main' "$$f" | sed -E "s/^[[:space:]]*object /$$pkg./"; \
	done | sort -u)

# *ChirrtlMain apps emit CHIRRTL (.fir) for the formal flow
CHIRRTL_MAINS := $(shell grep -rlE '^[[:space:]]*object [A-Za-z0-9_]+ChirrtlMain extends App' src/main/scala | while read -r f; do \
	pkg=$$(grep -m1 -E '^package ' "$$f" | sed -E 's/^package[[:space:]]+//'); \
	grep -oE '^[[:space:]]*object [A-Za-z0-9_]+ChirrtlMain' "$$f" | sed -E "s/^[[:space:]]*object /$$pkg./"; \
	done | sort -u)

FORCE ?= 0
CHISELSIM_CMD = $(if $(filter 1,$(FORCE)),testOnly *,test)
FIRTOOL      ?= firtool
BTORMC       ?= btormc
BTOR2_LAYERS ?= Verification,Verification.Assert,Verification.Assume
FORMAL_LOG   ?= generated/formal-summary.FORMAL_LOG
KMAX         ?= 20
TIMEOUT      ?= 20
MALLET_LOG   ?= generated/mallet-report.log

.PHONY: all chiselsim cocotb gen formal formal-gen btor2 mallet clean extraclean help

all: chiselsim cocotb

help:
	@echo "Targets:"
	@echo "  chiselsim  - run the Chisel scalatest suite (sbt test)"
	@echo "  cocotb     - run every cocotb testbench under tests/"
	@echo "  gen        - re-elaborate every Chisel App to SystemVerilog"
	@echo "  formal     - lower every *ChirrtlMain design to BTOR2 and BMC it (btormc)"
	@echo "  mallet     - per-property formal report (needs a mallet props sidecar)"
	@echo "               KMAX=<n> sets the btormc bound (default $(KMAX)) for formal+mallet"
	@echo "  clean      - clean generated SV, cocotb sim outputs, sbt target"

chiselsim:
	sbt "$(CHISELSIM_CMD)"

gen:
	sbt "$(foreach m,$(SBT_MAINS),; runMain $(m))"

btor2: formal

# for every *ChirrtlMain, Chisel --> chirrtl (.fir) 
formal-gen:
	sbt "$(foreach m,$(CHIRRTL_MAINS),; runMain $(m))"

mallet: formal-gen
	@mkdir -p "$$(dirname "$(MALLET_LOG)")"
	python3 scripts/mallet/run.py --kmax $(KMAX) --timeout $(TIMEOUT) 'generated/*/chirrtl/*.fir'; rc=$$?; \
	python3 scripts/mallet_dashboard.py; \
	echo "  dashboard: file://$$(pwd)/generated/mallet-dashboard.html"; \
	exit $$rc

cocotb:
	@for dir in $(COCOTB_DIRS); do \
		$(MAKE) -C $$dir sim || exit $$?; \
	done

clean:
	@for dir in $(COCOTB_DIRS); do \
		$(MAKE) -C $$dir clean || true; \
	done
	@[ -d generated ] && find generated -mindepth 1 -maxdepth 1 -type d -exec rm -rf {} + || true
	sbt clean

extraclean: clean
	-pkill -f sbt
	rm -rf target project/target project/project build .bsp