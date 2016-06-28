#!/bin/bash

set -eu

INPUT_FACTS=$1
OUTPUT_FACTS=$2
INTERMEDIATE_CONLL_X=`readlink -f $3`
INTERMEDIATE_CONLL_Y=`readlink -f $4`

JAR=target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar
if [[ ! -f $JAR ]]; then
  echo "make sure you build the jar first: $JAR"
  exit 1
fi

echo "Converting input to CoNLL format..."
echo "    $INPUT_FACTS"
echo "==> $INTERMEDIATE_CONLL_X"
if [[ `echo $INPUT_FACTS | grep -c '.gz$'` == 1 ]]; then
echo "unzipping on the fly"
time zcat $INPUT_FACTS | ./scripts/uberts/multidoc2conll.py /dev/stdin /dev/stdout \
  | awk -F"\t" 'BEGIN{OFS="\t"} {if(NF==10){$5="_"}; print}' >$INTERMEDIATE_CONLL_X
else
time ./scripts/uberts/multidoc2conll.py $INPUT_FACTS /dev/stdout \
  | awk -F"\t" 'BEGIN{OFS="\t"} {if(NF==10){$5="_"}; print}' >$INTERMEDIATE_CONLL_X
fi

echo "Running parsey mcparseface for POS and DEPREL prediction..."
echo "    $INTERMEDIATE_CONLL_X"
echo "==> $INTERMEDIATE_CONLL_Y"
time (cd ../tensorflow/models/syntaxnet && \
  ./syntaxnet/demo.sh --conll  <$INTERMEDIATE_CONLL_X >$INTERMEDIATE_CONLL_Y)

echo "Zipping up CoNLL parse output and original facts..."
echo "    $INTERMEDIATE_CONLL_Y"
echo "  + $INPUT_FACTS"
echo "==> $OUTPUT_FACTS"
time java -ea -cp $JAR \
  edu.jhu.hlt.uberts.io.ManyDocAndConllZipper \
    facts $INPUT_FACTS \
    conll $INTERMEDIATE_CONLL_Y \
    output $OUTPUT_FACTS

