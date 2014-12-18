
NUM_JOBS=500
WORKING_DIR=experiments/cache-parses

# NOTE set this to -Xmx5G for reduce

for i in `seq $NUM_JOBS`; do
  echo $i
  PIECE=`echo "$i - 1" | bc`
  qsub -q all.q CacheParsesHelper.qsub $WORKING_DIR map $PIECE $NUM_JOBS
done

