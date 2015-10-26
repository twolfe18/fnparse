
set -eu

if [[ $# != 9 ]]; then
  echo "please provide:"
  echo "1) a working directory"
  echo "2) a data directory (i.e. should be parent of coherent-shards-filtered/)"
  echo "3) propbank, i.e. either \"true\" or \"false\""
  echo "4) a dimension in [10, 20, 40, 80, 160, 320, 640]"
  echo "5) an oracle mode in [MIN, RAND_MIN, RAND_MAX, MAX]"
  echo "6) a beam size, e.g. [1, 4, 16, 64]"
  echo "7) force left right inference, i.e. either \"true\" or \"false\""
  echo "8) perceptron, i.e. either \"true\" or \"false\""
  echo "9) something to prefix job names and working directories with"
  echo "where options 3,4,5,6,7 may also take on the special value"
  echo "\"#\", which means sweep all values."
  exit 1
fi

# e.g. /export/projects/twolfe/fnparse-output/experiments/precompute-features/propbank/sep14b/final-results
WD=$1
DD=$2
PROPBANK=$3
SET_DIM=$4
SET_ORACLE_MODE=$5
SET_BEAM_SIZE=$6
SET_FORCE_LEFT_RIGHT=$7
SET_PERCEPTRON=$8
PREFIX=$9

mkdir -p $WD/sge-logs
#make jar
JAR=target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar
JAR_STABLE=$WD/fnparse.jar
echo "copying jar to a safe place:"
echo "    $JAR"
echo "==> $JAR_STABLE"
cp $JAR $JAR_STABLE


if [[ $PROPBANK == "true" ]]; then
  MEM_OFFSET=20
  MEM_SLOPE="0.11"
elif [[ $PROPBANK == "false" ]]; then
  MEM_OFFSET=5
  MEM_SLOPE="0.04"
else
  echo "must provide a boolean for propbank: $PROPBANK"
  exit 2
fi
echo "using data in $DD"

# DIM, ORACLE_MODE, BEAM_SIZE
# use hash to describe "all values" -- would use star but that requires escaping

for FORCE_LEFT_RIGHT in "true" "false"; do
if [[ $SET_FORCE_LEFT_RIGHT != "#" && $SET_FORCE_LEFT_RIGHT != $FORCE_LEFT_RIGHT ]]; then
  continue
fi
for PERCEPTRON in "true" "false"; do
if [[ $SET_PERCEPTRON != "#" && $SET_PERCEPTRON != $PERCEPTRON ]]; then
  continue
fi
for BEAM_SIZE in 1 4 16 64; do
if [[ $SET_BEAM_SIZE != "#" && $SET_BEAM_SIZE != $BEAM_SIZE ]]; then
  continue
fi
for ORACLE_MODE in MIN MAX RAND_MIN RAND_MAX; do
if [[ $SET_ORACLE_MODE != "#" && $SET_ORACLE_MODE != $ORACLE_MODE ]]; then
  continue
fi
for DIM in 10 20 40 80 160 320 640 1280; do
if [[ $SET_DIM != "#" && $SET_DIM != $DIM ]]; then
  continue
fi
for MODE in FULL LOCAL ARG-LOCATION NUM-ARGS ROLE-COOC; do
  MEM=`echo "$MEM_SLOPE * $DIM + $MEM_OFFSET" | bc | perl -pe 's/(\d+)\.\d+/\1/'`
  MEM_SGE=`echo "$MEM + 2" | bc`
  FF=scripts/having-a-laugh/propbank-${DIM}.fs
  WDJ=$WD/${PREFIX}-${BEAM_SIZE}-${ORACLE_MODE}-${MODE}-${DIM}
  mkdir -p $WDJ
  qsub \
    -l "mem_free=${MEM_SGE}G" \
    -N "${PREFIX}-${BEAM_SIZE}-${ORACLE_MODE}-${MODE}-${DIM}" \
    -o $WD/sge-logs \
    scripts/precompute-features/train.sh \
      $WDJ \
      $DD \
      $PROPBANK \
      $DIM \
      $ORACLE_MODE \
      $BEAM_SIZE \
      $FORCE_LEFT_RIGHT \
      $PERCEPTRON \
      $FF \
      $MODE \
      "${MEM}" \
      $JAR_STABLE
done
done
done
done
done
done

