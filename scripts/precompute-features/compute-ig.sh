#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=7G
#$ -l num_proc=1
#$ -S /bin/bash

# TODO NOTE DEPRECATED
# see feature-selection(-pos-helper).sh for the new way of doing this:
# use awk directly on feature files and pipe results to TemplateIG.

set -eu

echo "starting at `date` on $HOSTNAME"
echo "args: $@"

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

# Must be set in environment
FNPARSE_DATA=/export/projects/twolfe/fnparse-data/

# Can't use relative paths when you have a checkout in a temporary directory
# and don't use -cwd!
#-DbubFuncParentDir=scripts/precompute-features

# -Dunigrams=true is the flag to make InformationGainProducts
# behave like InformationGain and only compute IG for single templates.
java -Xmx${XMX}G -cp $JAR \
  -Dfeatures=$FEATS\
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
  edu.jhu.hlt.fnparse.features.precompute.featureselection.InformationGainProducts

echo "ret code: $?"
echo "done at `date`"

