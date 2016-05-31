#!/bin/bash

#SBATCH --nodes 1
#SBATCH --time 72:00:00
#SBATCH --mem 15G

#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=15G
#$ -l num_proc=1
#$ -S /bin/bash

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
if [[ ! -d $RD ]]; then
  echo "not a directory: $RD"
  exit 1
fi

TF="$RD/srl.train.shuf0.facts.gz"
for i in `seq 9`; do
  TF="$TF,$RD/srl.train.shuf${i}.facts.gz"
done
echo "TF=$TF"

java -cp $JAR_STABLE -ea -server -Xmx14G \
  edu.jhu.hlt.uberts.auto.UbertsLearnPipeline \
    miniDevSize 300 \
    trainSegSize 6000 \
    train.facts $TF \
    dev.facts $RD/srl.dev.facts.gz \
    test.facts $RD/srl.test.facts.gz \
    grammar $RD/grammar.trans \
    relations $RD/relations.def \
    schema $RD/frameTriage4.rel.gz,$RD/role2.rel.gz,$RD/spans.schema.facts.gz \
    priority $PRIORITY \
    globalFeatMode $GLOBAL_FEAT_MODE \
    featureSetDir $FEATURE_SET_DIR

echo "done at `date`, ret code $?"

