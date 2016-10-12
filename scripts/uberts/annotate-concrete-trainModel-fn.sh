#!/bin/bash

# Trains a Framenet SRL model for predicting event1, predicate2, argument4 facts.
# Packages everything up into the directory provided.

set -eu

OUTPUT_MODEL_DIR=$1

# This must contain things like:
#   $FNPARSE_DATA/embeddings/bc_out_256
#   $FNPARSE_DATA/wordnet/dict
# This directory holds everything which is not model-specific and
# is too big to duplicate many times.
FNPARSE_DATA=$2

JAR=$3




if [[ ! -d "$OUTPUT_MODEL_DIR" ]]; then
  mkdir -p "$OUTPUT_MODEL_DIR"
elif [[ `find "$OUTPUT_MODEL_DIR" | wc -l` != 1 ]]; then
  echo "output model directory must be empty: $OUTPUT_MODEL_DIR"
  exit 1
fi

if [[ ! -d "$FNPARSE_DATA" ]]; then
  echo "no FNPARSE_DATA: $FNPARSE_DATA"
  exit 2
fi

JAR_STABLE="$OUTPUT_MODEL_DIR/fnparse.jar"
echo "copying jar to model dir: $JAR_STABLE"
cp $JAR $JAR_STABLE

# easyfirst-static is best but requires extra train/test step
# easyfirst-dynamic is hit or miss
# freq is a good compromise
AGENDA_COMPARATOR="FREQ"
ARG4_FREQ_CACHE="$OUTPUT_MODEL_DIR/arg4-freq-cache.jser.gz"

GF="argument4_t^numArgs@F"

IO="event1+learn+write:$OUTPUT_MODEL_DIR/event1.model"
IO="$IO,predicate2+learn+write:$OUTPUT_MODEL_DIR/predicate2.model"
IO="$IO,argument4+learn+write:$OUTPUT_MODEL_DIR/argument4.model"
IO="$IO,argument4NilSpan+learn+write:$OUTPUT_MODEL_DIR/argument4NilSpan.model"
IO="$IO,${GF}+learn+write:$OUTPUT_MODEL_DIR/${GF}.model"

mkdir -p "$OUTPUT_MODEL_DIR/schema"
cp data/srl-reldata/framenet/frameTriage4.rel.gz "$OUTPUT_MODEL_DIR/schema"
cp data/srl-reldata/framenet/role2.observed.rel.gz "$OUTPUT_MODEL_DIR/schema"
cp data/srl-reldata/framenet/spans.schema.facts.gz "$OUTPUT_MODEL_DIR/schema"
cp data/srl-reldata/framenet/coarsenFrame2.identity.rel.gz "$OUTPUT_MODEL_DIR/schema"
cp data/srl-reldata/framenet/coarsenPos2.rel "$OUTPUT_MODEL_DIR/schema"
SCHEMA=`find "$OUTPUT_MODEL_DIR/schema" -type f | tr '\n' ','`
echo "schema: $SCHEMA"

GRAMMAR="$OUTPUT_MODEL_DIR/grammar.trans"
cp data/srl-reldata/grammar/srl-grammar-framenet.trans "$GRAMMAR"

RELATIONS="$OUTPUT_MODEL_DIR/relations.def"
cp data/srl-reldata/relations.def "$RELATIONS"

FEATURES="$OUTPUT_MODEL_DIR/feature_set"
cp -r data/feature-sets/semaforish "$FEATURES"

ARG4_COUNTS="$OUTPUT_MODEL_DIR/cacheArg4RoleFreqCounts.jser.gz"

cp -r data/pred2arg-paths "$OUTPUT_MODEL_DIR/pred2arg-paths"

#    miniDevSize 30 \
#    trainSegSize 500 \
#    trainTimeLimitMinutes 5 \

java -ea -cp "$JAR_STABLE" -Xmx5G \
  edu.jhu.hlt.uberts.auto.UbertsLearnPipeline \
    parameterIO "$IO" \
    agendaComparator $AGENDA_COMPARATOR \
    globalFeats "$GF" \
    trainMethod LASO2 \
    laso2OracleRollIn event1,predicate2 \
    costMode HINGE \
    byGroupDecoder "EXACTLY_ONE:predicate2(t,f):t EXACTLY_ONE:argument4(t,f,s,k):t:f:k" \
    grammar "$GRAMMAR" \
    train.facts data/srl-reldata/framenet/srl.train.shuf0.facts.gz \
    dev.facts data/srl-reldata/framenet/srl.dev.shuf.facts.gz \
    schema "$SCHEMA" \
    relations "$RELATIONS" \
    includeClassificationObjectiveTerm false \
    featureSetDir "$FEATURES" \
    cacheArg4RoleFreqCounts "$ARG4_COUNTS" \
    data.embeddings "$FNPARSE_DATA/embeddings" \
    data.wordnet "$FNPARSE_DATA/wordnet/dict" \
    pred2arg.feat.paths "$OUTPUT_MODEL_DIR/pred2arg-paths/propbank.txt" \
    rolePathCounts "$OUTPUT_MODEL_DIR/pred2arg-paths/propbank.byRole.txt" \
  | tee "$OUTPUT_MODEL_DIR/train-log.txt"


