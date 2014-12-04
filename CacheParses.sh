
NUM_JOBS=500
WORKING_DIR=experiments/cache-parses

for i in `seq $NUM_JOBS`; do
  echo $i
  qsub -q all.q CacheParsesHelper.qsub $WORKING_DIR map $i $NUM_JOBS
done

