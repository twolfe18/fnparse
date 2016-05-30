#!/bin/bash

#SBATCH --nodes 1
#SBATCH --time 48:00:00
#SBATCH --mem 12G

#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=2G
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

# A file with one feature per line, of the form "template1 * template2"
FEATURE_SET=$3

# A JAR file in a location which will not change/be removed.
JAR_STABLE=$4


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

java -cp $JAR_STABLE -ea -server -Xmx12G \
  edu.jhu.hlt.uberts.auto.UbertsLearnPipeline \
    featureSet foo \
    train.facts $TF \
    dev.facts $RD/srl.dev.facts.gz \
    test.facts $RD/srl.test.facts.gz \
    grammar $RD/grammar.trans \
    relations $RD/relations.def \
    schema $RD/frameTriage4.rel.gz,$RD/role2.rel.gz,$RD/spans.schema.facts.gz \
    priority $PRIORITY

echo "done at `date`, ret code $?"

