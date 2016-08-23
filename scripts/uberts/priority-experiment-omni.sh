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
#WD=`readlink -f $1`
WD=$1

# A transition grammar file
# e.g. data/srl-reldata/grammar/srl-grammar-propbank.trans
GRAMMAR=`readlink -f $2`

# Directory into which we can write dev/test predictions
#PREDICTIONS_DIR=`readlink -f $2`
PREDICTIONS_DIR=$3
if [[ "$PREDICTIONS_DIR" != "none" ]]; then
  echo "trying to resolve PREDICTIONS_DIR=$PREDICTIONS_DIR"
  PREDICTIONS_DIR=`readlink -f $PREDICTIONS_DIR`
fi

# This should be a string of the form "weight * priorityFuncName + ..."
# e.g. "1 * easyFirst + 1 + dfs"
# see AgendaPriority.java for a list of all legal values.
#PRIORITY=$4
# Comparators defined in edu.jhu.hlt.uberts.AgendaComparators separated by commas,
# e.g. "BY_RELATION,BY_TARGET,BY_FRAME,BY_ROLE,BY_SCORE"
#AGENDA_COMPARATOR=$4
# Should be a "<key> <value>" string where the key is either
# agendaComparator or hackyTFKReorderMethod
REORDER=$4

# Comma-separated list of relations which should be predicted correctly
# by the oracle, at train and test time.
ORACLE_RELATIONS=$5

# See ParameterSimpleIO.Instance2
# e.g. "predicate2+frozen+read:path,argument4+learn+write:path"
PARAM_IO=$6
echo "using PARAM_IO=$PARAM_IO"

# A directory which contains a <relationName>.fs file for every Relation.
# Each file is the 8-column TSV format.
FEATURE_SET_DIR=`readlink -f $7`

# Some combination of
# +frames, +none, argLoc, roleCooc, numArg, or full
GLOBAL_FEATS=$8

# Probably "MAX_VIOLATION", maybe "DAGGER1"
TRAIN_METHOD=$9

# A JAR file in a location which will not change/be removed.
JAR_STABLE=`readlink -f ${10}`
#JAR_STABLE=$9


if [[ ! -d $FEATURE_SET_DIR ]]; then
  echo "FEATURE_SET_DIR=$FEATURE_SET_DIR is not a directory"
  exit 2
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

echo "Using REORDER=$REORDER"

BY_GROUP_DECODER="EXACTLY_ONE:predicate2(t,f):t"
BY_GROUP_DECODER="$BY_GROUP_DECODER EXACTLY_ONE:argument4(t,f,s,k):t:k"

#SCHEMA="$RD/frameTriage4.rel.gz,$RD/role2.rel.gz,$RD/spans.schema.facts.gz,$RD/coarsenFrame2.rel.gz,$RD/coarsenPos2.rel"
SCHEMA="$RD/frameTriage4.rel.gz,$RD/role2.observed.rel.gz,$RD/spans.schema.facts.gz,$RD/coarsenFrame2.identity.rel.gz,$RD/coarsenPos2.rel"

#MINI_DEV_SIZE=200
#MINI_TRAIN_SIZE=1000
MINI_DEV_SIZE=300
MINI_TRAIN_SIZE=6000

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
    trainTimeLimitMinutes 0 \
    skipSrlFilterStages true \
    train.facts $TF \
    dev.facts $RD/srl.dev.shuf.facts.gz \
    test.facts $RD/srl.test.facts.gz \
    grammar $GRAMMAR \
    trainMethod $TRAIN_METHOD \
    relations $RD/relations.def \
    schema "$SCHEMA" \
    $REORDER \
    oracleRelations "$ORACLE_RELATIONS" \
    oracleFeatures "$ORACLE_RELATIONS" \
    byGroupDecoder "$BY_GROUP_DECODER" \
    globalFeats "$GLOBAL_FEATS" \
    parameterIO "$PARAM_IO" \
    featureSetDir $FEATURE_SET_DIR \
    learnDebug true \
    hackyImplementation true \
    agendaComparator 'BY_RELATION,BY_SCORE' \
    predictions.outputDir $PREDICTIONS_DIR

#agendaComparator $AGENDA_COMPARATOR
#hackyTFKReorderMethod

echo "done at `date`, ret code $?"


