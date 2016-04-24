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
FEATS=$1              # should be path+glob like "/foo/bar/**/*.txt"
BIALPH=$2             # names of features in FEATS_*
OUTPUT_IG_FILE=$3
ENTROPY_METHOD=$4     # BUB, MAP, or MLE
LABEL_TYPE=$5         # frames or roles
REFINEMENT_MODE=$6    # probably NULL_LABEL or FRAME
IS_PROPBANK=$7
ROLE_NAMES=$8         # e.g. $WORKING_DIR/raw-shards/job-0-of-256/role-names.txt.bz2
IGNORE_SENT_IDS=$9
NUM_ROLES=${10}
SHARD=${11}
JAR=${12}
XMX=${13}

# Which/how many products to compute IG for
FEATS_PER_SHARD=${15}

# IG of templates, used to prioritize products
TEMPLATE_IG_FILE=${16}

# Can't use relative paths when you have a checkout in a temporary directory
# and don't use -cwd!
#-DbubFuncParentDir=scripts/precompute-features

java -Xmx${XMX}G -cp $JAR \
  -Dfeatures=$FEATS\
  -Dpropbank=$IS_PROPBANK \
  -DentropyMethod=$ENTROPY_METHOD \
  -DlabelType=$LABEL_TYPE \
  -DrefinementMode=$REFINEMENT_MODE \
  -Dunigrams=false \
  -DroleNames=$ROLE_NAMES \
  -Dshard=$SHARD \
  -DnumProducts=$FEATS_PER_SHARD \
  -DtemplateAlph=$BIALPH \
  -DtemplateAlphLineMode=ALPH \
  -DtemplateIGs=$TEMPLATE_IG_FILE \
  -Doutput=$OUTPUT_IG_FILE \
  -DignoreSentenceIds=$IGNORE_SENT_IDS \
  -DbubFuncParentDir=/export/projects/twolfe/fnparse-data/matlab-code \
  -DnumRoles=$NUM_ROLES \
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

