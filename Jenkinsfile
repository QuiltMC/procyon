pipeline {
   agent any
   stages {

      stage ('Build') {
         when {
            branch 'hg_master'
         }
         steps {
            sh "rm -rf build/"
            sh "chmod +x gradlew"
            sh "./gradlew build publish -x test --refresh-dependencies --stacktrace"
         }
      }
   }
}