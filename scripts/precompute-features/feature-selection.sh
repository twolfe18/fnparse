
# A script to compute IG/MI in multiple ways.
# Assumes you've gotten through the coherent-shards-filtered step.

set -eu

WORKING_DIR=$1        # root of data dir, doesn't include e.g. $ENTROPY_METHOD
ENTROPY_METHOD=$2     # MAP, MLE, BUB
LABEL_TYPE=$3         # frames or roles
IS_PROPBANK=$4
JAR=$5
SUF=$6

TEST_SET_SENT_IDS=$WORKING_DIR/rel-data/test-set-sentence-ids.txt
if [[ ! -f $TEST_SET_SENT_IDS ]]; then
  echo "couldn't find test sent ids: $TEST_SET_SENT_IDS"
  exit 1
fi

# ig/
#   bub/
#     fnparse.jar
#     products/
#       ig-files/
#       sge-logs/
#     templates/
#       ig-files/
#       sge-logs/
#     feature-sets/
#       16-1280.fs

# This for the output for a specific run of this script
WD=$WORKING_DIR/ig/$ENTROPY_METHOD
echo "putting output in $WD"
#sleep 10  # a chance to kill/abort
mkdir -p $WD

JAR_STABLE=$WD/fnparse.jar
echo "copying the jar to a safe place..."
echo "    $JAR"
echo "==> $JAR_STABLE"
cp $JAR $JAR_STABLE

# Used for capturing SGE output, for example
TEMP=$WD/tempfile.txt


# Figure out how many roles there are.
# This is needed for indexing (y/role, x/feature) pairs.
# NOTE: index+1 goes from 0-index to count, and index+2 also accounts for
# InformationGain.ADD_ONE=true which uses -1 as "noRole" => 0 as "noRole".
ROLE_DICT="$WORKING_DIR/raw-shards/job-0-of-*/role-names.txt*"
if [[ $LABEL_TYPE == "roles" ]]; then
  NUM_ROLES=`/home/hltcoe/twolfe/bin/compress-cat $ROLE_DICT | grep -oP '\d+(?=	r=.+)' | tail -n 1 | awk '{print $1+2}'`
elif [[ $LABEL_TYPE == "frames" ]]; then
  NUM_ROLES=`/home/hltcoe/twolfe/bin/compress-cat $ROLE_DICT | grep -oP '\d+(?=	f=.+)' | tail -n 1 | awk '{print $1+2}'`
else
  echo "illegal LABEL_TYPE: $LABEL_TYPE"
  exit 2
fi
echo "computed there are $NUM_ROLES from looking at $ROLE_DICT"

TEMPLATE_NAMES=$WORKING_DIR/template-names.txt
if [[ ! -f $TEMPLATE_NAMES ]]; then
  echo "no template names file: $TEMPLATE_NAMES"
  exit 1
fi


### Step 0 ####################################################################
# Loading a giant bialph (which may even be bzip2'd) is slow and the resulting
# loaded representation is small (only has strings for template names).
# Do this once ahead of time and use java serialization for subsequent loads.
DD=$WORKING_DIR/coherent-shards-filtered
BIALPH_BIG=$DD/alphabet.txt$SUF
BIALPH=$DD/alphabet.jser.gz
if [[ ! -f $BIALPH_BIG ]]; then
  echo "bialph doesn't exist: $BIALPH_BIG"
  exit 2
fi
if [[ -f $BIALPH ]]; then
  echo "using pre-compiled jser bialph: $BIALPH"
else
  echo "making small jser bialph:"
  echo "$BIALPH_BIG  ==>  $BIALPH"
  time java -server -Xmx5G -ea -cp $JAR_STABLE \
    edu.jhu.hlt.fnparse.features.precompute.BiAlph \
      jser $BIALPH_BIG ALPH $BIALPH

  du -h $BIALPH_BIG $BIALPH

  echo "Done compiling jser bialph: $BIALPH"
  echo "Exiting now in case that is all you wanted to do."
  echo "To launch all the experiments, please re-run this same command"
  exit 0
fi


# THREE STEP PROCESS (holds for unigrams and ngrams)
# 1) extract with refinements
# 2) combine refinements (includes @y and @frame)
# 3) dedup



### Step 1 ####################################################################
echo "Computing IG for single templates..."
FEATS="$DD/features/**/*"
mkdir -p $WD/templates/ig-files
mkdir -p $WD/templates/sge-logs
MEM=12
MEM_SGE=`echo "$MEM+2" | bc`

# POS features, array job
CMD="scripts/precompute-features/feature-selection-pos-helper.qsub \
    $DD \
    $WD \
    $TEST_SET_SENT_IDS \
    $BIALPH \
    $ENTROPY_METHOD \
    $NUM_ROLES \
    $JAR_STABLE"
echo $CMD
J_POS=`qsub -o $WD/templates/sge-logs $CMD | get-sge-job-id`
echo "J_POS=$J_POS"

# Need to combine the pos-shard* outputs
# Wait on J_POS
FEAT_POS_BYFRAME_ALL=$WD/templates/ig-files/pos.unigram.txt.gz
NUM_SHARDS=128  # see helper script
CMD="zcat $WD/templates/ig-files/pos.unigram.byFrame-shard-*-of-${NUM_SHARDS}.txt* \
  | sort -rg -k2 \
  | gzip -c >$FEAT_POS_BYFRAME_ALL"
echo $CMD
J_FEAT_POS_BYFRAME_ALL=`qsub -hold_jid $J_POS \
  -l "h_rt=72:00:00,num_proc=1,mem_free=2G" \
  -b y -j y -o $WD/templates/sge-logs \
  $CMD | get-sge-job-id`
echo "J_FEAT_POS_BYFRAME_ALL=$J_FEAT_POS_BYFRAME_ALL"

# NEG features, single job
FEAT_NEG_ALL=$WD/templates/ig-files/neg.unigram.txt.gz
CMD="java -ea -cp $JAR_STABLE -server -Xmx${MEM}G \
  edu.jhu.hlt.fnparse.features.precompute.featureselection.InformationGainProducts \
  negUnigram true \
  entropyMethod $ENTROPY_METHOD \
  output $FEAT_NEG_ALL \
  bialph $BIALPH \
  numRoles $NUM_ROLES \
  ignoreSentenceIds $TEST_SET_SENT_IDS \
  features $DD/features/**/* \
  refinementShard 0/1"
echo $CMD
J_FEAT_NEG_ALL=`qsub -l "h_rt=72:00:00,num_proc=1,mem_free=${MEM_SGE}G" \
  -o $WD/templates/sge-logs -b y -j y $CMD | get-sge-job-id`
echo "J_FEAT_NEG_ALL=$J_FEAT_NEG_ALL"



### Step 2 ####################################################################
echo "Reducing POS@frame and NEG IG/MIs into single template scores..."
# TODO Need to capture the dependencies of the previous set of jobs.
# Call combine-mutual-information.py
# Crucial questions that I'd like answered:
# - exp vs max
# - score vs rank
# => this gives me 3 at a minimum, but I can just do the fourth side of the square for completeness
# exp+score (baseline)    1 0 0 1 0
# exp+rank                1 0 0 0 1
# max+score               0 1 0 1 0
# max+rank                1 0 0 0 1
mkdir -p $WD/templates/combined
mkdir -p $WD/templates/combined/sge-logs
for C_EXP in 0 1; do
for C_MAX in 0 1; do
for C_BOTH in 0 1; do
if [[ `echo "$C_EXP + $C_MAX + $C_BOTH" | bc` == 0 ]]; then
  continue
fi
for C_ABS in 0 1; do
for C_RBC in 0 1; do
if [[ `echo "$C_ABS + $C_RBC" | bc` == 0 ]]; then
  continue
fi

# Combine POS@frame => POS
POS_REDUCED=$WD/templates/combined/pos-${C_EXP}${C_MAX}${C_BOTH}${C_ABS}${C_RBC}.txt
CMD="compress-cat $FEAT_POS_BYFRAME_ALL \
    | PYTHONPATH=scripts/having-a-laugh python \
      scripts/precompute-features/combine-mutual-information.py \
        $C_EXP $C_MAX $C_BOTH $C_ABS $C_RBC  \
    | sort -rg -k2 \
    >$POS_REDUCED"
echo $CMD
J_POS_REDUCED=`qsub -hold_jid $J_FEAT_POS_BYFRAME_ALL \
  -o $WD/templates/combined/sge-logs \
  -l 'h_rt=72:00:00,num_proc=1,mem_free=12G' \
  -b y -j y $CMD | get-sge-job-id`
echo "J_POS_REDUCED=$J_POS_REDUCED"

# Combine POS and NEG into one.
# (so we have one possible input to the product selection phase per 10010 mask)
POS_AND_NEG=$WD/templates/combined/posAndNeg-${C_EXP}${C_MAX}${C_BOTH}${C_ABS}${C_RBC}.txt
CMD="compress-cat $POS_REDUCED $FEAT_NEG_ALL \
    | PYTHONPATH=scripts/having-a-laugh python \
      scripts/precompute-features/combine-mutual-information.py 1 0 0 1 0 \
    | sort -rg -k2 \
    >$POS_AND_NEG"
echo $CMD
J_POS_AND_NEG=`qsub -hold_jid "$J_FEAT_NEG_ALL,$J_POS_REDUCED" \
  -o $WD/templates/combined/sge-logs \
  -l 'h_rt=72:00:00,num_proc=1,mem_free=12G' \
  -b y -j y $CMD | get-sge-job-id`

# 3) dedup
CMD="compress-cat $POS_AND_NEG \
  | python scripts/having-a-laugh/dedup_sim_feats.py \
    5 \
    $POS_AND_NEG \
    3000 \
    $WD/templates/combined/posAndNeg-${C_EXP}${C_MAX}${C_BOTH}${C_ABS}${C_RBC}.dedup.txt"
echo $CMD
J_POS_AND_NEG_DEDUP=`qsub -hold_jid $J_POS_AND_NEG \
  -o $WD/templates/combined/sge-logs \
  -l 'h_rt=72:00:00,num_proc=1,mem_free=12G' \
  -b y -j y \
  $CMD | get-sge-job-id`

done
done
done
done
done


echo "there is no way the next step is going to work automatically"
exit 0


### Step 3 ####################################################################
./scripts/precompute-features/feature-selection2.sh
exit 0


### Step 4 ####################################################################
echo "Reducing template@frame@role PMI scores to single feature scores..."
# TODO
OUTPUT=$PROD_IG_WD/ig-files/shard-${I}-of-${NUM_SHARDS}.txt.gz


### Step 5 ####################################################################
echo "Computing feature sets based on IG scores and apparent redundancy (by feature name)..."
# TODO see scripts/having-a-laugh






