#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=7G
#$ -l num_proc=1
#$ -S /bin/bash

set -eu

echo "starting at `date` on $HOSTNAME"
echo "args: $@"

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

# Must be set in environment
#FNPARSE_DATA=/export/projects/twolfe/fnparse-data/

# Can't use relative paths when you have a checkout in a temporary directory
# and don't use -cwd!
#-DbubFuncParentDir=scripts/precompute-features

# -Dunigrams=true is the flag to make InformationGainProducts
# behave like InformationGain and only compute IG for single templates.
java -Xmx${XMX}G -cp $JAR \
  -DfeaturesParent=$FEATS_PARENT \
  -DfeaturesGlob=$FEATS_GLOB \
  -Dbialph=$BIALPH \
  -Dshard=$SHARD \
  -DnumRoles=$NUM_ROLES \
  -DoutputFeatures=$OUTPUT_IG_FILE \
  -Doutput=$OUTPUT_IG_FILE \
  -DignoreSentenceIds=$IGNORE_SENT_IDS \
  -DbubFuncParentDir=/export/projects/twolfe/fnparse-data/matlab-code \
  -Dpropbank=$IS_PROPBANK \
  -DentropyMethod=$ENTROPY_METHOD \
  -DlabelType=$LABEL_TYPE \
  -DrefinementMode=$REFINEMENT_MODE \
  -Dunigrams=true \
  -DroleNames=$ROLE_NAMES \
  -Ddata.framenet.root=$FNPARSE_DATA \
  -Ddata.wordnet=$FNPARSE_DATA/wordnet/dict \
  -Ddata.embeddings=$FNPARSE_DATA/embeddings \
  -Ddata.ontonotes5=$FNPARSE_DATA/LDC2013T19/data/files/data/english/annotations \
  -Ddata.propbank.conll=$FNPARSE_DATA/conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data \
  -Ddata.propbank.frames=$FNPARSE_DATA/ontonotes-release-5.0-fixed-frames/frames \
  -DwriteTopProductsEveryK=1 \
  edu.jhu.hlt.fnparse.features.precompute.featureselection.InformationGainProducts

echo "ret code: $?"
echo "done at `date`"

