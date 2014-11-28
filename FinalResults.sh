
CMD="qsub -q all.q FinalResultsHelper.qub"

SPAN_REGULAR="experiments/forward-selection/role/omni/wd-fs-test-0"
SPAN_LATENT="experiments/forward-selection/role/omni/wd-fs-test-0"
SPAN_NONE="experiments/forward-selection/role/omni/wd-fs-test-0"
HEAD_REGULAR="experiments/forward-selection/role/omni/wd-fs-test-0"
HEAD_LATENT="experiments/forward-selection/role/omni/wd-fs-test-0"
HEAD_NONE="experiments/forward-selection/role/omni/wd-fs-test-0"

SEED="9001"

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
  for N_TRAIN in 500 1000 1500 2000 2500 3000 3500; do
    $CMD $SPAN_REGULAR span $SEED $N_TRAIN
    $CMD $SPAN_LATENT span $SEED $N_TRAIN
    $CMD $SPAN_NONE span $SEED $N_TRAIN
    $CMD $HEAD_REGULAR head $SEED $N_TRAIN
    $CMD $HEAD_LATENT head $SEED $N_TRAIN
    $CMD $HEAD_NONE head $SEED $N_TRAIN
  done
done

