#!/bin/bash

#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=11G
#$ -l num_proc=1
#$ -S /bin/bash

#SBATCH --nodes 1
#SBATCH --time 72:00:00
#SBATCH --mem 11G

# Train a model by specifying an agenda priority function
# and as little else as possible.

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
#     grammar.trans
#     frameTriage4.rel.gz
#     role2.rel.gz
WD=`readlink -f $1`

# Directory into which we can write dev/test predictions
PREDICTIONS_DIR=`readlink -f $2`

# This should be a string of the form "weight * priorityFuncName + ..."
# e.g. "1 * easyFirst + 1 + dfs"
# see AgendaPriority.java for a list of all legal values.
PRIORITY=$3

# Where to get a pre-trained predicate2 model.
# If you pass in the string "oracle", we will just use oracle
# predicate2 decisions rather than look for a model.
PRED_MODEL_IN=$4

# A directory which contains a <relationName>.fs file for every Relation.
# Each file is the 8-column TSV format.
FEATURE_SET_DIR=`readlink -f $5`

# Either "none", "argLoc", "roleCooc", "numArg", or "full"
GLOBAL_FEAT_MODE=$6

# Where to save the arg id model produced.
ARG_MODEL_OUT=`readlink -f $7`

# A JAR file in a location which will not change/be removed.
JAR_STABLE=`readlink -f $8`


echo "checkpoint A"
if [[ ! -d $PREDICTIONS_DIR ]]; then
  echo "making $PREDICTIONS_DIR"
  mkdir -p $PREDICTIONS_DIR
fi

echo "checkpoint B"
if [[ ! -d $FEATURE_SET_DIR ]]; then
  echo "FEATURE_SET_DIR=$FEATURE_SET_DIR is not a directory"
  exit 2
fi

echo "checkpoint C"
if [[ -f $ARG_MODEL_OUT ]]; then
  echo "ARG_MODEL_OUT=$ARG_MODEL_OUT already exists!"
  exit 3
fi

RD=$WD/rel-data
if [[ ! -d $RD ]]; then
  echo "not a directory: $RD"
  exit 1
fi

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


# Lets you define this in your own environment
if [[ -z ${FNPARSE_DATA+x} ]]; then
  #FNPARSE_DATA=~/scratch/fnparse-data
  #FNPARSE_DATA=~/code/fnparse/toydata
  FNPARSE_DATA=/export/projects/twolfe/fnparse-data
  echo "setting FNPARSE_DATA=$FNPARSE_DATA"
fi


BY_GROUP_DECODER="EXACTLY_ONE:predicate2(t,f):t"
BY_GROUP_DECODER="$BY_GROUP_DECODER AT_MOST_ONE:argument4(t,f,s,k):t:k"
#BY_GROUP_DECODER="$BY_GROUP_DECODER AT_MOST_ONE:argument4(t,f,s,k):t:s"

#SCHEMA="$RD/frameTriage4.rel.gz,$RD/role2.rel.gz,$RD/spans.schema.facts.gz,$RD/coarsenFrame2.rel.gz,$RD/null-span1.facts,$RD/coarsenPos2.rel"
SCHEMA="$RD/frameTriage4.rel.gz,$RD/role2.rel.gz,$RD/spans.schema.facts.gz,$RD/coarsenFrame2.rel.gz,$RD/coarsenPos2.rel"

#MINI_DEV_SIZE=200
#MINI_TRAIN_SIZE=1000
MINI_DEV_SIZE=300
MINI_TRAIN_SIZE=6000


PARAM_IO="argument4:w:$ARG_MODEL_OUT"
if [[ $PRED_MODEL_IN == "oracle" ]]; then
  echo "using oracle predicate2"
  ORACLE_FEATS="event1,predicate2"
else
  PRED_MODEL_IN=`readlink -f $PRED_MODEL_IN`
  ORACLE_FEATS="event1"
  if [[ ! -f $PRED_MODEL_IN ]]; then
    echo "can't find predicate2 model: $PRED_MODEL_IN"
    exit 2
  fi
  PARAM_IO="$PARAM_IO,predicate2:r:$PRED_MODEL_IN"
fi
echo "using PARAM_IO=$PARAM_IO"


java -cp $JAR_STABLE -ea -server -Xmx10G \
  edu.jhu.hlt.uberts.auto.UbertsLearnPipeline \
    data.embeddings $FNPARSE_DATA/embeddings \
    data.wordnet $FNPARSE_DATA/wordnet/dict \
    data.propbank.frames $FNPARSE_DATA/ontonotes-release-5.0-fixed-frames/frames \
    pred2arg.feat.paths $FNPARSE_DATA/pred2arg-paths/propbank.txt \
    rolePathCounts $FNPARSE_DATA/pred2arg-paths/propbank.byRole.txt \
    miniDevSize $MINI_DEV_SIZE \
    trainSegSize $MINI_TRAIN_SIZE \
    passes 3 \
    srl2ByArg false \
    argument4ByArg false \
    skipSrlFilterStages true \
    train.facts $TF \
    dev.facts $RD/srl.dev.shuf.facts.gz \
    test.facts $RD/srl.test.facts.gz \
    grammar $RD/grammar.trans \
    relations $RD/relations.def \
    schema "$SCHEMA" \
    dontLearnRelations "event1,predicate2" \
    byGroupDecoder "$BY_GROUP_DECODER" \
    oracleFeats "$ORACLE_FEATS" \
    agendaPriority "$PRIORITY" \
    parameterIO "$PARAM_IO" \
    frameCooc false \
    globalFeatMode $GLOBAL_FEAT_MODE \
    featureSetDir $FEATURE_SET_DIR \
    predictions.outputDir $PREDICTIONS_DIR

echo "done at `date`, ret code $?"

