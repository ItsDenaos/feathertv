# R8 rules for FeatherTV. The activities and the service declared in the manifest are kept by the
# rules aapt2 generates (build/tmp/aapt-rules.txt); this file only asks for maximum shrinking.
-allowaccessmodification
-repackageclasses
-printmapping build/mapping.txt
