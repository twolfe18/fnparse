#$ -cwd
#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=20G
#$ -l num_proc=1
#$ -o logging/propbank/feature-selection
#$ -S /bin/bash

CP=`find target/ -name '*.jar' | tr '\n' ':'`
java -XX:+UseSerialGC -Xmx18G -ea -server -cp ${CP} \
  edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer "$@"


# wtf, doesn't work
# "Error: A JNI error has occurred, please check your installation and try again"
#M2_CP=`grep 'kind="var"' .classpath | perl -pe 's/.*path="M2_REPO\/(.+?.jar)"\/>/\/home\/travis\/.m2\/repository\/\1/' | tr '\n' ':'`
#CP=target/fnparse-1.0.5.jar
#java -Xmx12G -ea -server -cp "${CP}:${M2_CP}" \
#java -Xmx12G -ea -server -cp "${CP}" \
#  edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer "$@"


# I can't figure out how to enable asserts...
#mvn exec:java \
#  -Dexec.mainClass="edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer" \
#  -Dexec.args="arg0 arg1 arg2"
  

