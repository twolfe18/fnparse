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

# Same as compute-ig.sh
FEATS_PARENT=$1
FEATS_GLOB=$2
BIALPH=$3             # names of features in FEATS_*
OUTPUT_IG_FILE=$4
ENTROPY_METHOD=$5     # BUB, MAP, or MLE
LABEL_TYPE=$6         # frames or roles
REFINEMENT_MODE=$7    # probably NULL_LABEL or FRAME
IS_PROPBANK=$8
ROLE_NAMES=$9         # e.g. $WORKING_DIR/raw-shards/job-0-of-256/role-names.txt.bz2
IGNORE_SENT_IDS=${10}
NUM_ROLES=${11}
SHARD=${12}
JAR=${13}
XMX=${14}

# Which/how many products to compute IG for
FEATS_PER_SHARD=${15}

# IG of templates, used to prioritize products
TEMPLATE_IG_FILE=${16}

# Can't use relative paths when you have a checkout in a temporary directory
# and don't use -cwd!
#-DbubFuncParentDir=scripts/precompute-features

java -Xmx${XMX}G -cp $JAR \
  -Dpropbank=$IS_PROPBANK \
  -DentropyMethod=$ENTROPY_METHOD \
  -DlabelType=$LABEL_TYPE \
  -DrefinementMode=$REFINEMENT_MODE \
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

