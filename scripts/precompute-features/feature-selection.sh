
# A script to compute IG/MI in multiple ways

set -eu

WORKING_DIR=$1        # root of data dir, doesn't include e.g. $ENTROPY_METHOD
TEST_SET_SENT_IDS=$2
ENTROPY_METHOD=$3     # MAP, MLE, BUB
JAR=$4
SUF=$5

# This for the output for a specific run of this script
WD=$WORKING_DIR/ig/$ENTROPY_METHOD
echo "putting output in $WD"
sleep 10  # a chance to kill/abort

JAR=target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar
JAR_STABLE=$WD/fnparse.jar
echo "copying the jar to a safe place..."
echo "    $JAR"
echo "==> $JAR_STABLE"
cp $JAR $JAR_STABLE

TEMP=$WD/tempfile.txt


# Do a 1/10 estimate of 1/10 of the data and average the answers (should take 1/10th the time but be approximate)
N=10
XMX=32
XMX_SGE=36
mkdir -p $WD/templates/split-$N-filter10
for i in `seq $N | awk '{print $1-1}'`; do
  qsub -N split$N-filter10-$i-ig-template \
    -l "mem_free=${XMX_SGE}G" \
    -o $WORKING_DIR/ig/templates/sge-logs \
    scripts/precompute-features/compute-ig.sh \
      $WORKING_DIR/coherent-shards-filtered/features \
      "glob:**/*1${i}.txt$SUF" \
      $WORKING_DIR/ig/templates/split-$N-filter10/shard-${i}.txt \
      $JAR_STABLE \
      $TEST_SET_SENT_IDS \
      $NUM_ROLES \
      $XMX
done


# Do a 1/10 estimate and average the answers (should take 1/10th the time but be approximate)
N=10
XMX=60
XMX_SGE=65
mkdir -p $WD/ig/templates/split-$N
for i in `seq $N | awk '{print $1-1}'`; do
  qsub -N split$N-$i-ig-template \
    -l "mem_free=${XMX_SGE}G" \
    -o $WORKING_DIR/ig/templates/sge-logs \
    scripts/precompute-features/compute-ig.sh \
      $WORKING_DIR/coherent-shards-filtered/features \
      "glob:**/*${i}.txt$SUF" \
      $WD/templates/split-$N/shard-${i}.txt \
      $JAR_STABLE \
      $TEST_SET_SENT_IDS \
      $NUM_ROLES \
      $XMX
done >$TEMP

# Do a full/proper estimate
XMX=80
XMX_SGE=85
qsub -N full-ig-template \
  -l "mem_free=${XMX_SGE}G" \
  -o $WD/templates/sge-logs \
  scripts/precompute-features/compute-ig.sh \
    $WORKING_DIR/coherent-shards-filtered/features \
    "glob:**/*" \
    $WD/templates/full-ig.txt \
    $JAR_STABLE \
    $TEST_SET_SENT_IDS \
    $NUM_ROLES \
    $XMX


### After those jobs finish, average things
DEPS=`grep -oP '(?<=Your job )(\d+)' $TEMP | tr '\n' ',' | perl -pe 's/(.*),/\1\n/'`
qsub -cwd -l "mem_free=1G,num_proc=1,h_rt=12:00:00" -b y -hold_jid $DEPS \
  python scripts/precompute-features/average_ig2.py \
    $WD/templates/split-$N/average.txt \
    $WD/templates/split-$N/shard-* >$TEMP


# 2) For products of templates, filtered by top K products ranked by product of template IG
# NOTE: The more shards the more products we compute on
DEPS=`grep -oP '(?<=Your job )(\d+)' $TEMP | tr '\n' ',' | perl -pe 's/(.*),/\1\n/'`
TEMPLATE_IG_FILE=$WORKING_DIR/ig/templates/full-ig.txt
PROD_IG_WD=$WORKING_DIR/ig/products
mkdir -p $PROD_IG_WD/ig-files
mkdir -p $PROD_IG_WD/sge-logs
NUM_SHARDS=500
FEATS_PER_SHARD=500
for i in `seq $NUM_SHARDS | awk '{print $1 - 1}'`; do
  qsub -hold_jid $DEPS -N prod-ig-$i -o $PROD_IG_WD/sge-logs \
    ./scripts/precompute-features/compute-ig-products.sh \
      $i \
      $NUM_SHARDS \
      $FEATS_PER_SHARD \
      $TEMPLATE_IG_FILE \
      $WORKING_DIR/coherent-shards-filtered/features \
      "glob:**/*" \
      $WORKING_DIR/coherent-shards-filtered/alphabet.txt$SUF \
      $TEST_SET_SENT_IDS \
      $PROD_IG_WD/ig-files/shard-${i}-of-${NUM_SHARDS}.txt \
      $JAR_STABLE \
      $NUM_ROLES
done


# 3) TODO pull in code from scripts/having-a-laugh/Makefile


# ig/
#   bub/
#     fnparse.jar
#     products/
#       ig-files/
#       sge-logs/
#     templates/
#       full
#       split10
#       split10-filter10
#       sge-logs/
#     feature-sets/
#       16-1280.fs




