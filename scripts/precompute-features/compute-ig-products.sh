#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=12G
#$ -l num_proc=1
#$ -S /bin/bash

# Given IG estimates for templates and int features, take a shard-wise slice of
# all possible products up to 3-grams and compute their IG.
# Wrapper around edu.jhu.hlt.fnparse.features.precompute.InformationGainProducts

echo "starting at `date` on $HOSTNAME"
echo "args: $@"

set -eu

## if [[ $# != 11 ]]; then
##   echo "please provide:"
##   echo "1) shard"
##   echo "2) number of shards"
##   echo "3) number of features per shard to compute -- affects memory usage, try 500"
##   echo "4) IG of templates (<ig> <tab> <template int> -- should match alphabet below)"
##   echo "5) feature parent directory, e.g \$WD/coherent-shards/features"
##   echo "6) feature files glob, e.g. \"glob:**/*\""
##   echo "7) alphabet -- can be configured to take bialphs (TODO)"
##   echo "8) a file containing sentence ids to ignore"
##   echo "9) an output file to dump template product (i.e. feature) IGs to"
##   echo "10) a JAR file"
##   echo "11) number of roles expected"
##   echo "where:"
##   echo "a) shards sub-select over products (not data)"
##   echo "b) feature file should be coherent -- share a single alphabet (provided)"
##   echo "c) the jar file is in a stable location"
##   exit 1
## fi

# Same as compute-ig.sh
FEATS_PARENT=$1
FEATS_GLOB=$2
BIALPH=$3             # names of features in FEATS_*
OUTPUT_IG_FILE=$4
ENTROPY_METHOD=$5     # BUB, MAP, or MLE
LABEL_TYPE=$6         # frames or roles
IS_PROPBANK=$7
ROLE_NAMES=$8         # e.g. $WORKING_DIR/raw-shards/job-0-of-256/role-names.txt.bz2
IGNORE_SENT_IDS=$9
NUM_ROLES=${10}
SHARD=${11}
JAR=${12}
XMX=${13}

# Which/how many products to compute IG for
FEATS_PER_SHARD=${14}

# IG of templates, used to prioritize products
TEMPLATE_IG_FILE=${15}

# Can't use relative paths when you have a checkout in a temporary directory
# and don't use -cwd!
#-DbubFuncParentDir=scripts/precompute-features

java -Xmx${XMX}G -cp $JAR \
  -Dpropbank=$IS_PROPBANK \
  -DentropyMethod=$ENTROPY_METHOD \
  -DlabelType=$LABEL_TYPE \
  -Dunigrams=false \
  -Dshard=$SHARD \
  -DnumProducts=$FEATS_PER_SHARD \
  -DfeaturesParent=$FEATS_PARENT \
  -DfeaturesGlob=$FEATS_GLOB \
  -DtemplateAlph=$BIALPH \
  -DtemplateAlphLineMode=ALPH \
  -DtemplateIGs=$TEMPLATE_IG_FILE \
  -Doutput=$OUTPUT_IG_FILE \
  -DignoreSentenceIds=$IGNORE_SENT_IDS \
  -DbubFuncParentDir=/export/projects/twolfe/fnparse-data/matlab-code \
  -DnumRoles=$NUM_ROLES \
  edu.jhu.hlt.fnparse.features.precompute.featureselection.InformationGainProducts

echo "ret code: $?"
echo "done at `date`"

