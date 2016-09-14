#!/bin/bash

set -eu

# Input: A <communicationId>.conll file (e.g. output of parsey with words, POS tags, and deps)
# Input: A <communicationId>.sentenceMetaInfo file containing the section info for each sentence (one sentence per line)
# Output: A <communicationId>.comm concrete file

TOOL_NAME=$1
INPUT_CONLL_FILE=$2
INPUT_META_DIR=$3
OUTPUT_COMM_DIR=$4
CJ_CONLL_JAR=$5

INPUT_META_FILE=$INPUT_META_DIR/`basename $INPUT_CONLL_FILE | perl -pe 's/.conll$/.sentenceMetaInfo/'`
if [[ ! -f $INPUT_META_FILE ]]; then
	echo "can't find sentence meta info file: $INPUT_META_FILE"
	exit 1
fi

OUTPUT_COMM_FILE=$OUTPUT_COMM_DIR/`basename $INPUT_CONLL_FILE | perl -pe 's/.conll$/.comm/'`

COMM_ID=`basename $INPUT_CONLL_FILE | perl -pe 's/.conll$//'`

java -ea -cp $CJ_CONLL_JAR \
	edu.jhu.hlt.concrete.ingesters.conll.CoNLLX \
		"$COMM_ID" \
		"$TOOL_NAME" \
		$INPUT_CONLL_FILE \
		$OUTPUT_COMM_FILE \
		$INPUT_META_FILE

