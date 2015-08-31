#$ -cwd
#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=13G
#$ -l num_proc=1
#$ -o logging/propbank
#$ -S /bin/bash

set -euo pipefail

cd data/cache/parses/propbank/redis

redis-server

