#curl -s "https://get.sdkman.io" | bash
#source "$HOME/.sdkman/bin/sdkman-init.sh"

#sdk install java 17.0.20-tem
#sdk install gradle 7.6.4

#java -version            # should say 17
#gradle -v                # should say 7.6.4

#gradle wrapper
./gradlew buildPlugin     # → build/distributions/lm-chat-0.1.0.zip
#./gradlew runIde          # optional: sandbox IDE (downloads IC 2022.1.3)
#./gradlew prepareSandbox --continuous