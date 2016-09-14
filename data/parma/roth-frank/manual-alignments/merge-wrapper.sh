#!/bin/bash

set -eu

INPUT_STANFORD_COMM_FILE=$1		# e.g. concrete-stanford-comms/APW_ENG_19950111.0161.comm
INPUT_PARSEY_COMM_DIR=$2		# e.g. concrete-parsey/
OUTPUT_COMM_DIR=$3				# e.g. concrete-parsey-and-stanford/
CJ_UTIL_JAR=$4					# concrete-java jar containing MergeTokenAlignedCommunications

if [[ ! -f $CJ_UTIL_JAR ]]; then
	echo "JAR doesn't exist: $CJ_UTIL_JAR"
	exit 1
fi

INPUT_PARSEY_COMM_FILE=$INPUT_PARSEY_COMM_DIR/`basename $INPUT_STANFORD_COMM_FILE`
OUTPUT_COMM_FILE=$OUTPUT_COMM_DIR/`basename $INPUT_STANFORD_COMM_FILE`

if [[ ! -f $INPUT_STANFORD_COMM_FILE ]]; then
	echo "stanford comm doesn't exist: $INPUT_STANFORD_COMM_FILE"
	exit 2
fi
if [[ ! -f $INPUT_PARSEY_COMM_FILE ]]; then
	echo "parsey comm doesn't exist: $INPUT_PARSEY_COMM_FILE"
	exit 3
fi

echo "   $INPUT_STANFORD_COMM_FILE"
echo " + $INPUT_PARSEY_COMM_FILE"
echo " = $OUTPUT_COMM_FILE"

java -ea -cp $CJ_UTIL_JAR \
	edu.jhu.hlt.concrete.merge.MergeTokenAlignedCommunications \
		$INPUT_STANFORD_COMM_FILE \
		$INPUT_PARSEY_COMM_FILE \
		$OUTPUT_COMM_FILE

