#!/bin/bash

set -eu

ENTITY_DIR_PARENT=$1
JAR=$2

export GUROBI_HOME=/home/hltcoe/twolfe/gurobi/gurobi702/linux64
export LD_LIBRARY_PATH=/home/hltcoe/twolfe/gurobi/gurobi702/linux64/lib
export GRB_LICENSE_FILE=/home/hltcoe/twolfe/gurobi/keys/gurobi.lic
export HOSTNAME="caligula"

for PARSE in `find $ENTITY_DIR_PARENT -name 'parse.conll'`; do
ENT_DIR=`dirname $PARSE`
mkdir -p $ENT_DIR/summary
#qsub -cwd -b y -j y -o $ENT_DIR/summary -l "mem_free=2G,num_proc=1,h_rt=8:00" \
	java -ea -server -cp fnparse.jar edu.jhu.hlt.entsum.SlotsAsConcepts \
		mode summarize \
		pmiFiles "feature-selection/**/mi-withNeg-entityInst-shard*-of16.txt" \
		entityDir $ENT_DIR \
		outputDir $ENT_DIR/summary \
		numWords "40,80,160,320"
done

