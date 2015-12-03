#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=20G
#$ -l num_proc=1
#$ -S /bin/bash

set -e

echo "starting at `date` on $HOSTNAME"
echo "args: $@"

#if [[ $# != 3 ]]; then
#  echo "please provide:"
#  echo "1) a working directory"
#  echo "2) an output file for (template, information-gain) pairs"
#  echo "3) a jar file"
#  echo "where a \"working directory\" is a directory made by a FeaturePrecomputation job"
#  echo "which contains a file called features.txt.gz and template-feat-indices.txt.gz"
#  exit 1
#fi
#
#WORKING_DIR=$1
#OUTPUT=$2
#JAR=$3
#
#java -Xmx3G -ea -server -cp $JAR \
#  -Dfeatures=$WORKING_DIR/features.txt.gz \
#  -DtemplateAlph=$WORKING_DIR/template-feat-indices.txt.gz \
#  -DtopK=30 \
#  -DoutputFeats=$OUTPUT \
#  edu.jhu.hlt.fnparse.features.precompute.InformationGain

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

java -Xmx${XMX}G -cp $JAR \
  -DfeaturesParent=$FEATS_PARENT \
  -DfeaturesGlob=$FEATS_GLOB \
  -DtopK=99999 \
  -DnumRoles=$NUM_ROLES \
  -DoutputFeatures=$OUTPUT_IG_FILE \
  -DignoreSentenceIds=$TEMPLATE_PROD_IG_OUTPUT \
  -DbubFuncParentDir=scripts/precompute-features \
  -DnumTemplates=30000 \
  edu.jhu.hlt.fnparse.features.precompute.InformationGain

echo "ret code: $?"
echo "done at `date`"

