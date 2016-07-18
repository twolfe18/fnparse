
set -eu

if [[ $# == 0 ]]; then
JAR=`realpath target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar`
else
JAR=`realpath $1`
fi

T=`mktemp -d`
cd $T
jar xf $JAR build-info.txt
cat build-info.txt
cd
rm -rf $T

