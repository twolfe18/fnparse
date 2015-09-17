#$ -cwd
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

if [[ `echo $2 | grep -cP 'gz$'` == 1 ]]; then
  awk -F"\t" 'BEGIN{OFS="\t"} {print $1, $2, $3, $4}' <$1 | gzip -c >$2
else
  awk -F"\t" 'BEGIN{OFS="\t"} {print $1, $2, $3, $4}' <$1 >$2
fi

echo "ret code: $?"
echo "done at `date`"

