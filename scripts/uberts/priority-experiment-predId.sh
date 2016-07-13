#!/bin/bash

#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=10G
#$ -l num_proc=1
#$ -S /bin/bash

#SBATCH --nodes 1
#SBATCH --time 72:00:00
#SBATCH --mem 10G

# Train a predicate id model

set -eu

echo "starting at `date` on $HOSTNAME"
echo "args: $@"


# Directory which holds extracted features, etc.
# e.g. /export/projects/twolfe/fnparse-output/experiments/???/propbank
# WD/
#   rel-data/
#     train.0.facts.multi ... train.9.facts.multi
#     dev.facts.multi
#     test.facts.multi
#     relations.def
#     grammar.predicate2.trans
#     frameTriage4.rel.gz
#     role2.rel.gz
WD=`readlink -f $1`

# Directory into which we can write dev/test predictions
PREDICTIONS_DIR=`readlink -f $2`

# true or false, include frameCooc feature?
GLOBAL=$3

# true or false
L2R=$4

# A directory which contains a <relationName>.fs file for every Relation.
# Each file is the 8-column TSV format.
FEATURE_SET_DIR=`readlink -f $5`

# Where to write the predicate2.jser.gz model file
PRED_MODEL_OUT=`readlink -f $6`

# A JAR file in a location which will not change/be removed.
JAR_STABLE=`readlink -f $7`



echo "checkpoint A"
if [[ -f $PRED_MODEL_OUT ]]; then
  echo "output file already exists: $PRED_MODEL_OUT"
  exit 1
fi
PARAM_IO="predicate2:w:$PRED_MODEL_OUT"
echo "PARAM_IO=$PARAM_IO"


echo "checkpoint B"
RD=$WD/rel-data
#RD=$WD/rel-data/old
if [[ ! -d $RD ]]; then
  echo "not a directory: $RD"
  exit 1
fi


GRAMMAR=$RD/grammar.predicate2.trans
echo "GRAMMAR=$GRAMMAR"
if [[ ! -f $GRAMMAR ]]; then
  echo "grammar doesn't exist: $GRAMMAR"
  exit 1
fi

echo "checkpoint C"
if [[ ! -d $FEATURE_SET_DIR ]]; then
  echo "FEATURE_SET_DIR=$FEATURE_SET_DIR is not a directory"
  exit 2
fi

echo "checkpoint=D"
if [[ ! -d $PREDICTIONS_DIR ]]; then
  echo "making $PREDICTIONS_DIR"
  mkdir -p $PREDICTIONS_DIR
fi

echo "checkpoint E"
if [[ $L2R == "true" ]]; then
PRIORITY="1 * leftright + 0.001 * easyfirst"
else
PRIORITY="1 * easyfirst"
fi
echo "PRIORITY=$PRIORITY"


TF="$RD/srl.train.shuf0.facts.gz"
for i in `seq 9`; do
  F=$RD/srl.train.shuf${i}.facts.gz
  if [[ -f $F ]]; then
  TF="$TF,$F"
  else
  echo "WARNING: Missing training file: $F"
  fi
done
echo "TF=$TF"

#FNPARSE_DATA=~/scratch/fnparse-data
#FNPARSE_DATA=~/code/fnparse/toydata

# Lets you define this in your own environment
if [[ -z ${FNPARSE_DATA+x} ]]; then
  FNPARSE_DATA=/export/projects/twolfe/fnparse-data
  echo "setting FNPARSE_DATA=$FNPARSE_DATA"
fi


echo "checkpoint F"
#SCHEMA="$RD/frameTriage4.rel.gz,$RD/role2.rel.gz,$RD/spans.schema.facts.gz,$RD/coarsenFrame2.rel.gz,$RD/null-span1.facts,$RD/coarsenPos2.rel"
SCHEMA="$RD/frameTriage4.rel.gz,$RD/role2.rel.gz,$RD/spans.schema.facts.gz,$RD/coarsenFrame2.rel.gz,$RD/coarsenPos2.rel"

BY_GROUP_DECODER="EXACTLY_ONE:predicate2(t,f):t"

#MINI_DEV_SIZE=200
#MINI_TRAIN_SIZE=1000
MINI_DEV_SIZE=300
MINI_TRAIN_SIZE=6000

java -cp $JAR_STABLE -ea -server -Xmx9G \
  edu.jhu.hlt.uberts.auto.UbertsLearnPipeline \
    data.embeddings $FNPARSE_DATA/embeddings \
    data.wordnet $FNPARSE_DATA/wordnet/dict \
    data.propbank.frames $FNPARSE_DATA/ontonotes-release-5.0-fixed-frames/frames \
    pred2arg.feat.paths $FNPARSE_DATA/pred2arg-paths/propbank.txt \
    rolePathCounts $FNPARSE_DATA/pred2arg-paths/propbank.byRole.txt \
    miniDevSize $MINI_DEV_SIZE \
    trainSegSize $MINI_TRAIN_SIZE \
    passes 3 \
    trainTimeLimitMinutes 0 \
    passiveAggressive true \
    train.facts $TF \
    dev.facts $RD/srl.dev.shuf.facts.gz \
    test.facts $RD/srl.test.facts.gz \
    grammar $GRAMMAR \
    relations $RD/relations.def \
    schema "$SCHEMA" \
    byGroupDecoder "$BY_GROUP_DECODER" \
    oracleFeats event1 \
    agendaPriority "$PRIORITY" \
    parameterIO "$PARAM_IO" \
    frameCooc $L2R \
    globalFeatMode none \
    featureSetDir $FEATURE_SET_DIR \
    predictions.outputDir $PREDICTIONS_DIR

echo "done at `date`, ret code $?"

