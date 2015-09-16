#$ -cwd
#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=16G
#$ -l num_proc=1
#$ -S /bin/bash

# Just a wrapper around AlphabetMerger.

set -e

echo "arguments: $@"

if [[ $# != 4 ]]; then
  echo "please provide:"
  echo "1) input working directory A"
  echo "2) input working directory B"
  echo "3) output working directory"
  echo "4) a jar file in a stable location"
  echo "where a \"working directory\" is a directory made by a FeaturePrecomputation job"
  echo "which contains a file called features.txt.gz and template-feat-indices.txt.gz"
  exit 1
fi

JOB_DIR_A=$1
JOB_DIR_B=$2
OUTPUT_DIR=$3
JAR=$4

# Old version that does extra feature copying
#java -Xmx15G -ea -server -cp $JAR \
#  -DfeatIn1=$JOB_DIR_A/features.txt.gz \
#  -DalphIn1=$JOB_DIR_A/template-feat-indices.txt.gz \
#  -DfeatIn2=$JOB_DIR_B/features.txt.gz \
#  -DalphIn2=$JOB_DIR_B/template-feat-indices.txt.gz \
#  -DfeatOut=$OUTPUT_DIR/features.txt.gz \
#  -DalphOut=$OUTPUT_DIR/template-feat-indices.txt.gz \
#  edu.jhu.hlt.fnparse.features.precompute.AlphabetMerger

# Sort the incoming alphabets
A1=$JOB_DIR_A/template-feat-indices.txt.gz
A2=$JOB_DIR_B/template-feat-indices.txt.gz

A1_SORTED=$JOB_DIR_A/template-feat-indices.sorted.txt
A2_SORTED=$JOB_DIR_B/template-feat-indices.sorted.txt

echo "`date` sorting $A1  =>  $A1_SORTED"
LC_ALL=C sort -t '	' -k 3,4 -o $A1_SORTED <$(zcat $A1 | tail -n+2)

echo "`date` sorting $A2  =>  $A2_SORTED"
LC_ALL=C sort -t '	' -k 3,4 -o $A2_SORTED <$(zcat $A2 | tail -n+2)

A1_OUTPUT=$JOB_DIR_A/template-feat-indices.sorted.txt
A2_OUTPUT=$JOB_DIR_B/template-feat-indices.sorted.txt

echo "`date` done sorting, about to merge"
java -Xmx1G -ea -server -cp $JAR \
  -DinAlph1=$A1_SORTED \
  -DinAlph2=$A2_SORTED \
  -DoutAlph1=$OUTPUT_DIR/template-feat-indices.sorted.txt \
  edu.jhu.hlt.fnparse.features.precompute.AlphabetMerger

echo "`date` done"

