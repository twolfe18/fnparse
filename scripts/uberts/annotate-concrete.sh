#!/bin/bash

# Reads in a .tgz Communications file and runs UbertsLearnPipeline
# to add SituationMentionSet SRL annotations.

set -eu

INPUT_COMM_FILE=$1      # .tar.gz Communications
INPUT_MODEL_DIR=$2      # See scripts/uberts/annotate-concrete-trainModel*.sh
OUTPUT_COMM_FILE=$3     # .tar.gz Communications
FNPARSE_DATA=$4         # e.g. /export/projects/twolfe/fnparse-data
TOOLNAME=$5             # e.g. "fnparse/fn"
SITUATION_TYPE=$6       # e.g. "EVENT"


### 0) Check data dependencies
JAR="$INPUT_MODEL_DIR/fnparse.jar"
if [[ ! -f $JAR ]]; then
  echo "can't find jar: $JAR"
  exit 1
fi

if [[ ! -d $FNPARSE_DATA ]]; then
  echo "can't find FNPARSE_DATA: $FNPARSE_DATA"
  exit 2
fi

GRAMMAR="$INPUT_MODEL_DIR/grammar.trans"
if [[ ! -f $GRAMMAR ]]; then
  echo "can't find grammar file: $GRAMMAR"
  exit 3
fi

if [[ ! -d $INPUT_MODEL_DIR/schema ]]; then
  echo "can't find schema files"
  exit 4
fi
SCHEMA=`find $INPUT_MODEL_DIR/schema -type f | tr '\n' ','`

ARG4_FREQ_CACHE="$INPUT_MODEL_DIR/cacheArg4RoleFreqCounts.jser.gz"
if [[ ! -f $ARG4_FREQ_CACHE ]]; then
  echo "can't find $ARG4_FREQ_CACHE"
  exit 5
fi


### 1) Convert Communications into startdoc/sentences
#TEMP_FACT_RAW_FILE=/tmp/tmp.AABIyxyrMd.facts.gz
TEMP_FACT_RAW_FILE=`mktemp --suffix ".facts.gz"`
java -ea -cp $JAR \
  edu.jhu.hlt.uberts.srl.ConcreteToRelations \
    $INPUT_COMM_FILE \
    $TEMP_FACT_RAW_FILE

# POST-PROCESS: map dsyn3-basic => dsyn3-parsey since
# only concrete-stanford has run on the CAG
T=`mktemp --suffix ".facts.gz"`
zcat $TEMP_FACT_RAW_FILE \
  | grep -v 'dsyn3-col' \
  | perl -pe 's/ dsyn3-basic / dsyn3-parsey /' \
  | gzip -c >$T
mv $T $TEMP_FACT_RAW_FILE


### 2) Call UbertsLearnPipeline with test.facts from above
###    write out startdoc|predicate2|argument4 facts to another file
TEMP_FACT_ANNO_DIR=`mktemp -d`
# easyfirst-static is best but requires extra train/test step
# easyfirst-dynamic is hit or miss
# freq is a good compromise
AGENDA_COMPARATOR="FREQ"
GF="argument4_t^numArgs@F"
IO="event1+fixed+read:$INPUT_MODEL_DIR/event1.model"
IO="$IO,predicate2+fixed+read:$INPUT_MODEL_DIR/predicate2.model"
IO="$IO,argument4+fixed+read:$INPUT_MODEL_DIR/argument4.model"
IO="$IO,argument4NilSpan+fixed+read:$INPUT_MODEL_DIR/argument4NilSpan.model"
IO="$IO,${GF}+fixed+read:$INPUT_MODEL_DIR/${GF}.model"
java -ea -cp "$JAR" -Xmx5G \
  edu.jhu.hlt.uberts.auto.UbertsLearnPipeline \
    test.facts $TEMP_FACT_RAW_FILE \
    predictions.outputDir "$TEMP_FACT_ANNO_DIR" \
    parameterIO "$IO" \
    agendaComparator "$AGENDA_COMPARATOR" \
    cacheArg4RoleFreqCounts "$ARG4_FREQ_CACHE" \
    skipDocsWithoutPredicate2 false \
    globalFeats "$GF" \
    byGroupDecoder "EXACTLY_ONE:predicate2(t,f):t EXACTLY_ONE:argument4(t,f,s,k):t:f:k" \
    computeXuePalmerForWithoutEvent1Predictions true \
    grammar "$GRAMMAR" \
    schema "$SCHEMA" \
    relations "$INPUT_MODEL_DIR/relations.def" \
    featureSetDir "$INPUT_MODEL_DIR/feature_set" \
    data.embeddings "$FNPARSE_DATA/embeddings" \
    data.wordnet "$FNPARSE_DATA/wordnet/dict" \
    pred2arg.feat.paths "$INPUT_MODEL_DIR/pred2arg-paths/propbank.txt" \
    rolePathCounts "$INPUT_MODEL_DIR/pred2arg-paths/propbank.byRole.txt" \
    skipInferenceMaxNumTokens 0 \
    skipInferenceDocIds /dev/null   # can be comma separated multiple files

echo ""
echo "DONE WITH PARSING"
TEMP_FACT_ANNO_FILE="$TEMP_FACT_ANNO_DIR/test-full-epoch0.facts.gz"
echo "looking in $TEMP_FACT_ANNO_FILE"
echo ""

### 3) Zip up facts and Communications
java -ea -cp $JAR \
  edu.jhu.hlt.uberts.io.MergeFactsIntoSituationMentionSets \
    $INPUT_COMM_FILE \
    $TEMP_FACT_ANNO_FILE \
    $OUTPUT_COMM_FILE \
    "$TOOLNAME" \
    "$SITUATION_TYPE"

rm $TEMP_FACT_RAW_FILE
rm $TEMP_FACT_ANNO_FILE

