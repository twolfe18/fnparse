#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=2G
#$ -l num_proc=1
#$ -S /bin/bash

# Given a bialph, project down to an alph using (newInt,newString)

echo "starting at `date` on $HOSTNAME"
echo "args: $@"

set -e

if [[ $# != 2 ]]; then
  echo "please provide:"
  echo "1) an input bialph"
  echo "2) an output alph"
  exit 1
fi

CAT=""
if [[ `echo $1 | grep -cP 'gz$'` == 1 ]]; then
  CAT="zcat"
elif [[ `echo $1 | grep -cP 'bz2$'` == 1 ]]; then
  CAT="bzcat"
else
  CAT="cat"
fi

ZIP=""
if [[ `echo $2 | grep -cP 'gz$'` == 1 ]]; then
  ZIP="gzip -c"
elif [[ `echo $2 | grep -cP 'bz2$'` == 1 ]]; then
  ZIP="bzip2 -c"
else
  ZIP="cat"
fi

echo "CAT=\"$CAT\""
echo "ZIP=\"$ZIP\""

$CAT $1 | awk -F"\t" 'BEGIN{OFS="\t"} {print $1, $2, $3, $4}' | $ZIP >$2

echo "ret code: $?"
echo "done at `date`"

