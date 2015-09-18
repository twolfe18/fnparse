#$ -cwd
#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=2G
#$ -l num_proc=1
#$ -S /bin/bash

# Given an alphabet, make a bialph.

set -e

echo "starting at `date` on $HOSTNAME"
echo "args: $@"

if [[ $# != 2 ]]; then
  echo "please provide:"
  echo "1) an input alphabet (tab separated, header, cols: intTemplate, intFeature, stringTemplate, stringFeature)"
  echo "2) an output bialph"
  exit 1
fi

INPUT=$1  #/tmp/merged-0/template-feat-indices.txt.gz
OUTPUT=$2 #/tmp/merged-0/template-feat-indices.bialph.txt

# NOTE that awk is being used to add two more columns for the
# conversion between alphabet (4 columns) and bialphs (6 columns).

# NOTE that LC_ALL=C is needed because someone thought the default
# local shouldn't consider "<S>" and "</S>" to be lexicographically different...

TEMP=`mktemp`
if [[ `echo $INPUT | grep -cP 'gz$'` == 1 ]]; then
  zcat $INPUT | tail -n+2 >$TEMP
else
  tail -n+2 <$INPUT >$TEMP
fi

if [[ `echo $OUTPUT | grep -cP 'gz$'` == 1 ]]; then
  echo "using gzip"
  LC_ALL=C sort -t '	' -k 3,4 <$TEMP \
    | awk -F"\t" 'BEGIN{OFS="\t"}{$5=$1; $6=$2; print}' \
    | gzip -c \
    >$OUTPUT
else
  echo "using raw txt"
  LC_ALL=C sort -t '	' -k 3,4 <$TEMP \
    | awk -F"\t" 'BEGIN{OFS="\t"}{$5=$1; $6=$2; print}' \
    >$OUTPUT
fi

echo "ret code: $?"

rm $TEMP

echo "done at `date`"

