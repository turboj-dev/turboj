require 'open3'
require 'rexml/document'
base = '/private/tmp/turboj-completion-20261116'
mvn = '/Users/luca/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn'
[['mini', 'mini-java-spring-mcap'], ['shop-java','shop-java-api']].each do |project, module_path|
  [false, true].each do |native|
    output = "#{base}/#{project}-#{native ? 'native' : 'standalone'}-effective.xml"
    env = {'CHERRY_TEST_IDE_EVENTS'=>native ? "#{base}/profile-only.jsonl" : nil,
           'CHERRY_TEST_PROVENANCE_TOKEN'=>'00000000-0000-0000-0000-000000000001'}
    log,status = Open3.capture2e(env,mvn,'-B','-ntp','help:effective-pom',"-Doutput=#{output}", chdir: "#{base}/#{project}/#{module_path}")
    puts log
    raise "model failed #{project}" unless status.success?
    xml=REXML::Document.new(File.read(output))
    build=xml.root.elements['build'].to_s
    active=build.include?('CherryProvenance') || build.include?('provenance.jar')
    raise "incorrect profile activation #{project}/#{native}" unless active==native
    puts "PASS #{project} native=#{native}: provenance profile #{active ? 'active' : 'inactive'}"
  end
end
