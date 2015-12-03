#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=12G
#$ -l num_proc=1
#$ -S /bin/bash

# Given IG estimates for templates and int features, take a shard-wise slice of
# all possible products up to 3-grams and compute their IG.
# Wrapper around edu.jhu.hlt.fnparse.features.precompute.InformationGainProducts

set -e

echo "starting at `date` on $HOSTNAME"
echo "args: $@"

if [[ $# != 11 ]]; then
  echo "please provide:"
  echo "1) shard"
  echo "2) number of shards"
  echo "3) number of features per shard to compute -- affects memory usage, try 500"
  echo "4) IG of templates (<ig> <tab> <template int> -- should match alphabet below)"
  echo "5) feature parent directory, e.g \$WD/coherent-shards/features"
  echo "6) feature files glob, e.g. \"glob:**/*\""
  echo "7) alphabet -- can be configured to take bialphs (TODO)"
  echo "8) a file containing sentence ids to ignore"
  echo "9) an output file to dump template product (i.e. feature) IGs to"
  echo "10) a JAR file"
  echo "11) number of roles expected"
  echo "where:"
  echo "a) shards sub-select over products (not data)"
  echo "b) feature file should be coherent -- share a single alphabet (provided)"
  echo "c) the jar file is in a stable location"
  exit 1
fi

# Which/how many products to compute IG for
SHARD=$1
NUM_SHARDS=$2
FEATS_PER_SHARD=$3

# IG of templates, used to prioritize products
TEMPLATE_IG_FILE=$4

# Data/input
FEATS_PARENT=$5
FEATS_GLOB=$6
ALPH=$7         # Can configure if this needs to be a bialph, but should be an alph as is

# list of sentence ids (i.e. of the test set)
IGNORE_SENT_IDS=$8

# Output
TEMPLATE_PROD_IG_OUTPUT=$9

JAR=${10}

NUM_ROLES=${11}

java -Xmx11G -cp $JAR \
  -Dshard=$SHARD \
  -DnumShards=$NUM_SHARDS \
  -DnumProducts=$FEATS_PER_SHARD \
  -DfeaturesParent=$FEATS_PARENT \
  -DfeaturesGlob=$FEATS_GLOB \
  -DtemplateAlph=$ALPH \
  -DtemplateIsBialph=false \
  -DtemplateIGs=$TEMPLATE_IG_FILE \
  -Doutput=$TEMPLATE_PROD_IG_OUTPUT \
  -DignoreSentenceIds=$IGNORE_SENT_IDS \
  -DbubFuncParentDir=scripts/precompute-features \
  -DnumRoles=$NUM_ROLES \
  -DnumTemplates=12000 \
  edu.jhu.hlt.fnparse.features.precompute.InformationGainProducts

echo "ret code: $?"
echo "done at `date`"

