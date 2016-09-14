#!/bin/bash

# Input: A communication file (which we want to dump just the words for)
# Output1: A <communicationId>.conll file containing the words
# Output2: A <communicationId>.sentenceMetaInfo file containing the section info for each sentence (one sentence per line)

set -eu

INPUT_COMM_FILE=$1
OUTPUT_CONLL_DIR=$2
CJ_CONLL_JAR=$3

OUTPUT_CONLL_FILE=$OUTPUT_CONLL_DIR/`basename $INPUT_COMM_FILE | perl -pe 's/.comm$/.conll/'`
OUTPUT_META_FILE=$OUTPUT_CONLL_DIR/`basename $INPUT_COMM_FILE | perl -pe 's/.comm$/.sentenceMetaInfo/'`

time java -ea -cp $CJ_CONLL_JAR \
	edu.jhu.hlt.concrete.ingesters.conll.ConcreteToCoNLLX \
		$INPUT_COMM_FILE \
		$OUTPUT_CONLL_FILE \
		$OUTPUT_META_FILE

