#!/bin/bash

#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=8G
#$ -l num_proc=1
#$ -S /bin/bash

#SBATCH --nodes 1
#SBATCH --time 72:00:00
#SBATCH --mem 8G

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
WD=$1

# This should be a string of the form "weight * priorityFuncName + ..."
# e.g. "1 * easyFirst + 1 + dfs"
# see AgendaPriority.java for a list of all legal values.
PRIORITY=$2

# A directory which contains a <relationName>.fs file for every Relation.
# Each file is the 8-column TSV format.
FEATURE_SET_DIR=$3

# Either "none", "argLoc", "roleCooc", "numArg", or "full"
GLOBAL_FEAT_MODE=$4

# A JAR file in a location which will not change/be removed.
JAR_STABLE=$5


if [[ ! -d $FEATURE_SET_DIR ]]; then
  echo "FEATURE_SET_DIR=$FEATURE_SET_DIR is not a directory"
  exit 1
fi

RD=$WD/rel-data
#RD=$WD/rel-data/old
if [[ ! -d $RD ]]; then
  echo "not a directory: $RD"
  exit 1
fi

TF="$RD/srl.train.shuf0.fixed.facts.gz"
for i in `seq 9`; do
  F=$RD/srl.train.shuf${i}.fixed.facts.gz
  if [[ -f $F ]]; then
  TF="$TF,$F"
  else
  echo "WARNING: Missing training file: $F"
  fi
done
echo "TF=$TF"

#FNPARSE_DATA=~/scratch/fnparse-data
#FNPARSE_DATA=~/code/fnparse/toydata
FNPARSE_DATA=/export/projects/twolfe/fnparse-data

THRESHOLDS="srl2=-3 srl3=-3"
BY_GROUP_DECODER="EXACTLY_ONE:predicate2(t,f):t AT_MOST_ONE:argument4(t,f,s,k):t:s AT_MOST_ONE:argument4(t,f,s,k):t:k"

# TODO Make trainSegSize bigger when you launch for real

java -cp $JAR_STABLE -ea -server -Xmx7G \
  edu.jhu.hlt.uberts.auto.UbertsLearnPipeline \
    data.embeddings $FNPARSE_DATA/embeddings \
    data.wordnet $FNPARSE_DATA/wordnet/dict \
    data.propbank.frames $FNPARSE_DATA/ontonotes-release-5.0-fixed-frames/frames \
    pred2arg.feat.paths $FNPARSE_DATA/pred2arg-paths/propbank.txt \
    miniDevSize 300 \
    trainSegSize 6000 \
    predicate2.hashBits 25 \
    argument4.hashBits 26 \
    srl2.hashBits 25 \
    srl3.hashBits 25 \
    srl2ByArg false \
    argument4ByArg false \
    skipSrlFilterStages true \
    passiveAggressive true \
    addLhsScoreToRhsScore false \
    train.facts $TF \
    dev.facts $RD/srl.dev.shuf.fixed.facts.gz \
    test.facts $RD/srl.test.facts.gz \
    grammar $RD/grammar.trans \
    relations $RD/relations.def \
    schema $RD/frameTriage4.rel.gz,$RD/role2.rel.gz,$RD/spans.schema.facts.gz,$RD/coarsenFrame2.rel.gz,$RD/null-span1.facts,$RD/coarsenPos2.rel \
    thresholdNOPE "$THRESHOLDS" \
    byGroupDecoder "$BY_GROUP_DECODER" \
    oracleFeats event1,predicate2 \
    agendaPriority "$PRIORITY" \
    globalFeatMode $GLOBAL_FEAT_MODE \
    featureSetDir $FEATURE_SET_DIR

echo "done at `date`, ret code $?"

