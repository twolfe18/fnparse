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

# A JAR file in a location which will not change/be removed.
JAR_STABLE=$3


RD=$WD/rel-data
if [[ ! -d $RD ]]; then
  echo "not a directory: $RD"
  exit 1
fi

TF="$RD/train.0.facts.multi"
for i in `seq 9`; do
  TF="$TF,$RD/train.${i}.facts.multi"
done

java -cp $JAR -ea -server -Xmx12G \
  edu.jhu.hlt.uberts.auto.UbertsLearnPipeline \
  train.facts $TF \
  dev.facts $RD/dev.facts.multi \
  test.facts $RD/test.facts.multi \
  grammar $RD/grammar.trans \
  relation $RD/relations.def \
  schema $RD/frameTriage4.rel.gz,$RD/role2.rel.gz \
  priority $PRIORITY

echo "done at `date`, ret code $?"

