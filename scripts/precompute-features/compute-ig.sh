#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=20G
#$ -l num_proc=1
#$ -S /bin/bash

set -e

echo "starting at `date` on $HOSTNAME"
echo "args: $@"

if [[ $# != 7 ]]; then
  echo "please provide:"
  echo "1) a feature file parent directory"
  echo "2) a feature file glob"
  echo "3) an output file to put template IG estimates in"
  echo "4) a jar file"
  echo "5) a set of test set sentence ids to ignore"
  echo "6) number of roles expected"
  echo "7) how many GB of memory to give to the JVM (e.g. \"20\", NOT \"20G\")"
  exit 1
fi

FEATS_PARENT=$1
FEATS_GLOB=$2
OUTPUT_IG_FILE=$3
JAR=$4
TEMPLATE_PROD_IG_OUTPUT=$5
NUM_ROLES=$6
XMX=$7

# Can't use relative paths when you have a checkout in a temporary directory
# and don't use -cwd!
#-DbubFuncParentDir=scripts/precompute-features

java -Xmx${XMX}G -cp $JAR \
  -DfeaturesParent=$FEATS_PARENT \
  -DfeaturesGlob=$FEATS_GLOB \
  -DtopK=99999 \
  -DnumRoles=$NUM_ROLES \
  -DoutputFeatures=$OUTPUT_IG_FILE \
  -DignoreSentenceIds=$TEMPLATE_PROD_IG_OUTPUT \
  -DbubFuncParentDir=/export/projects/twolfe/fnparse-data/matlab-code \
  edu.jhu.hlt.fnparse.features.precompute.InformationGain

echo "ret code: $?"
echo "done at `date`"

