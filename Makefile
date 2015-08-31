
jar:
	rm -f target/*.jar
	mvn compile assembly:single -DskipTests

install:
	mvn install -DskipTests -Dgpg.skip=true





# Feature selection based on information gain
experiments/feature-information-gain/feature-sets/fs-%.txt:
	cat \
		experiments/feature-information-gain/aug31a/featIG.order1-thin30-*-of-200.txt \
		experiments/feature-information-gain/aug31a/featIG.order2-thin30-*-of-200.txt \
		experiments/feature-information-gain/aug31a/featIG.order3-thin10-*-of-200.txt \
		| sort -rg | head -n $* \
		| awk -F"\t" '{if(NR>1) printf " + "; printf "\%s", $$2}END{print ""}' \
		>experiments/feature-information-gain/feature-sets/fs-$*.txt

