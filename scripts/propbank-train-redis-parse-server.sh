#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=13G
#$ -l num_proc=1
#$ -S /bin/bash

set -euo pipefail

echo "starting at `date`"

if [[ $# != 1 ]]; then
  echo "please provide a redis config file, which specifies what redis' working directory should be"
  exit 1
fi

#cd data/cache/parses/propbank/redis
#cd /export/projects/twolfe/fnparse-data/cache/parses/propbank/redis

#/export/projects/twolfe/bin/redis-server $1
redis-server $1

