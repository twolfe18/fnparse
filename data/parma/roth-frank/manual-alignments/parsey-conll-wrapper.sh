#!/bin/bash

set -eu

# Input: A <communicationId>.conll file with just words
# Output: A <communicationId>.conll file with words + POS + deps from parsey
# Does not care about sections.

INPUT_CONLL_FILE=$1
OUTPUT_CONLL_DIR=$2
CP_HOME=$3

# e.g. "conllx-raw/APW_ENG_19950111.0161.conll" => "conllx-parsey/APW_ENG_19950111.0161.conll"
OUTPUT_CONLL_FILE=$OUTPUT_CONLL_DIR/`basename $INPUT_CONLL_FILE`
echo "parsing conll from $INPUT_CONLL_FILE"
echo "and then writing conll to $OUTPUT_CONLL_FILE"

# Need abs path because of cd-ing into concrete-parsey dir
OUTPUT_CONLL_FILE=`readlink -f $OUTPUT_CONLL_FILE`

# CP == concrete-parsey
#CP_HOME=/home/travis/code/concrete-parsey

if [[ ! -d $CP_HOME ]]; then
	echo "concrete-parsey home not a dir: $CP_HOME"
	exit 1
fi

cat $INPUT_CONLL_FILE | (cd $CP_HOME && ./scripts/parsey-docker-wrapper-local.sh >$OUTPUT_CONLL_FILE)


