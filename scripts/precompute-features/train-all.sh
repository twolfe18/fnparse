
set -eu

if [[ $# != 11 ]]; then
  echo "please provide:"
  echo "1) a working directory"
  echo "2) a data directory (i.e. should be parent of coherent-shards-filtered/)"
  echo "3) propbank, i.e. either \"true\" or \"false\""
  echo "4) a dimension in [10, 20, 40, 80, 160, 320, 640]"
  echo "5) an oracle mode in [MIN, RAND_MIN, RAND_MAX, MAX]"
  echo "6) a beam size, e.g. [1, 4, 16, 64]"
  echo "7) force left right inference, i.e. either \"true\" or \"false\""
  echo "8) perceptron, i.e. either \"true\" or \"false\""
  echo "9) how many data points to limit training to"
  echo "10) regularizer strength, e.g. \"6\" => L2_LOCAL=1e-6 L2_GLOBAL=5e-6"
  echo "11) something to prefix job names and working directories with"
  echo "where options 3,4,5,6,7 may also take on the special value"
  echo "\"#\", which means sweep all values."
  echo ""
  echo "you provided: $@"
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
SET_NTRAIN=$9
SET_REG=${10}
PREFIX=${11}

mkdir -p $WD/sge-logs
#make jar
JAR=target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar
JAR_STABLE=$WD/fnparse.jar
echo "copying jar to a safe place:"
echo "    $JAR"
echo "==> $JAR_STABLE"
cp $JAR $JAR_STABLE


if [[ $PROPBANK == "true" ]]; then
  #MEM_OFFSET=22
  #MEM_SLOPE="0.11"
  MEM_OFFSET=16
  MEM_SLOPE="0.09"
  NTRAIN_VALUES=(1000 3000 9000 27000 0)
elif [[ $PROPBANK == "false" ]]; then
  #MEM_OFFSET=7
  #MEM_SLOPE="0.04"
  MEM_OFFSET=7
  MEM_SLOPE="0.035"
  NTRAIN_VALUES=(100 500 1000 2000 0)
else
  echo "must provide a boolean for propbank: $PROPBANK"
  exit 2
fi
echo "using data in $DD"

# DIM, ORACLE_MODE, BEAM_SIZE
# use hash to describe "all values" -- would use star but that requires escaping

for NTRAIN in ${NTRAIN_VALUES[@]}; do
if [[ $SET_NTRAIN != "#" && $SET_NTRAIN != $NTRAIN ]]; then
  continue
fi
for FORCE_LEFT_RIGHT in "true" "false"; do
if [[ $SET_FORCE_LEFT_RIGHT != "#" && $SET_FORCE_LEFT_RIGHT != $FORCE_LEFT_RIGHT ]]; then
  continue
fi
for PERCEPTRON in "true" "false"; do
if [[ $SET_PERCEPTRON != "#" && $SET_PERCEPTRON != $PERCEPTRON ]]; then
  continue
fi
for BEAM_SIZE in 1 2 4 8 16 32 64; do
if [[ $SET_BEAM_SIZE != "#" && $SET_BEAM_SIZE != $BEAM_SIZE ]]; then
  continue
fi
for ORACLE_MODE in MIN MAX RAND_MIN RAND_MAX; do
if [[ $SET_ORACLE_MODE != "#" && $SET_ORACLE_MODE != $ORACLE_MODE ]]; then
  continue
fi
#for DIM in 80 160 320 640 1280 2560; do
# {8,16,32,mix} x {40,160,640}
# 4 * 3 = 12
# twice as slow as previously, but i only do it once (sweep-dim)...
# If I remove mix and 640, then
# 3 * 2 = 6  => just as fast as before (faster actually, smaller feature sets)
for DIM in 8-40 16-40 32-40 64-40 mix-40 8-160 16-160 32-160 64-160 mix-160 8-640 16-640 32-640 64-640 mix-640; do
#for DIM in 8-40 16-40 32-40 mix-40 8-160 16-160 32-160 mix-160 8-640 16-640 32-640 mix-640; do
#for DIM in 16-40 32-40 mix-40 8-160 16-160 32-160 mix-160 8-640 16-640 32-640; do
if [[ $SET_DIM != "#" && $SET_DIM != $DIM ]]; then
  continue
fi
for REG in 5 6 7 8 9; do
if [[ $SET_REG != "#" && $SET_REG != $REG ]]; then
  continue
fi
L2_LOCAL="1e-$REG"
L2_GLOBAL="5e-$REG"

for MODE in FULL LOCAL ARG-LOCATION NUM-ARGS ROLE-COOC; do
  D=`echo $DIM | cut -d'-' -f2`
  MEM=`echo "$MEM_SLOPE * $D + $MEM_OFFSET" | bc | perl -pe 's/(\d+)\.\d+/\1/'`
  MEM_LIMIT=70
  if [[ $MEM -gt $MEM_LIMIT ]]; then
    echo "MEM TOO BIG: $MEM"
    MEM=$MEM_LIMIT
  fi
  MEM_SGE=`echo "$MEM + 4" | bc`
  echo "MEM=$MEM MEM_SGE=$MEM_SGE"
  F_HOME=/home/hltcoe/twolfe/fnparse
  if [[ $PROPBANK == "true" ]]; then
    FF=$F_HOME/scripts/having-a-laugh/propbank-${DIM}.fs
  elif [[ $PROPBANK == "false" ]]; then
    FF=$F_HOME/scripts/having-a-laugh/framenet-${DIM}.fs
  else
    echo "need boolean: $PROPBANK"
    exit 2
  fi
  if [[ ! -f $FF ]]; then
    echo "couldn't find: $FF"
    exit 3
  fi
  KEY="$PREFIX-${REG}-$NTRAIN-$FORCE_LEFT_RIGHT-$PERCEPTRON-$BEAM_SIZE-$ORACLE_MODE-$DIM-$MODE"
  WDJ="$WD/$KEY"
  mkdir -p $WDJ

  echo "a L2_LOCAL=$L2_LOCAL"
  echo "a L2_GLOBAL=$L2_GLOBAL"

  qsub \
    -l "mem_free=${MEM_SGE}G" \
    -N "$KEY" \
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
      $NTRAIN \
      $FF \
      $MODE \
      $L2_LOCAL \
      $L2_GLOBAL \
      "${MEM}" \
      $JAR_STABLE
done
done
done
done
done
done
done
done

