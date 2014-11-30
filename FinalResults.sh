
CMD="qsub -q all.q FinalResultsHelper.qsub"

SPAN_REGULAR="experiments/forward-selection/role/omni/wd-fs-test-462"
SPAN_LATENT="experiments/forward-selection/role/omni/wd-fs-test-331"
SPAN_NONE="experiments/forward-selection/role/omni/wd-fs-test-1700"
HEAD_REGULAR="experiments/forward-selection/role/omni/wd-fs-test-285"
HEAD_LATENT="experiments/forward-selection/role/omni/wd-fs-test-1498"
HEAD_NONE="experiments/forward-selection/role/omni/wd-fs-test-1751"

SEED="9001"

echo "WARNING: about to DELETE previous RESULTS"
echo "you have 10 seconds to CANCEL, ctrl-c"
sleep 10
find experiments/forward-selection/role/omni -wholename "*wd-fs-test-*/finalResults.txt" -type f | xargs rm
rm -f experiments/final-results/sge-logs/FinalResultsHelper.qsub.o*

# Start the sanity check versions (no re-training, just use the model
# created during feature selection).
echo "starting simple pre-trained jobs"
$CMD $SPAN_REGULAR span $SEED -1
$CMD $SPAN_LATENT span $SEED -1
$CMD $SPAN_NONE span $SEED -1
$CMD $HEAD_REGULAR head $SEED -1
$CMD $HEAD_LATENT head $SEED -1
$CMD $HEAD_NONE head $SEED -1

# Start the versions that are the full training set (no resampling)
echo "starting full re-train jobs"
$CMD $SPAN_REGULAR span $SEED 0
$CMD $SPAN_LATENT span $SEED 0
$CMD $SPAN_NONE span $SEED 0
$CMD $HEAD_REGULAR head $SEED 0
$CMD $HEAD_LATENT head $SEED 0
$CMD $HEAD_NONE head $SEED 0

# Start the learning curve jobs
for SEED in `seq 100`; do
  echo "starting learning curve seed $SEED"
  for N_TRAIN 153559 56491 20782 7645 2813 1035; do
    $CMD $SPAN_REGULAR span $SEED $N_TRAIN
    $CMD $SPAN_LATENT span $SEED $N_TRAIN
    $CMD $SPAN_NONE span $SEED $N_TRAIN
    $CMD $HEAD_REGULAR head $SEED $N_TRAIN
    $CMD $HEAD_LATENT head $SEED $N_TRAIN
    $CMD $HEAD_NONE head $SEED $N_TRAIN
  done
done

