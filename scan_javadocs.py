import os
import re

def find_java_files(root_dir):
    java_files = []
    for root, dirs, files in os.walk(root_dir):
        for file in files:
            if file.endswith(".java"):
                java_files.append(os.path.join(root, file))
    return java_files

def check_javadoc(file_path):
    with open(file_path, 'r') as f:
        lines = f.readlines()

    content = "".join(lines)

    # improved regex to catch methods and classes
    # strict public method/class detection is hard with regex, but we can approximate

    missing_docs = []

    # We will iterate line by line to keep track of javadocs
    in_javadoc = False
    javadoc_buffer = []
    last_javadoc_end_line = -1

    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("/**"):
            in_javadoc = True
            javadoc_buffer = []

        if in_javadoc:
            javadoc_buffer.append(stripped)
            if stripped.endswith("*/"):
                in_javadoc = False
                last_javadoc_end_line = i

        # Check for class/interface/enum definition
        # public class|interface|enum Name
        if "public" in stripped and ("class " in stripped or "interface " in stripped or "enum " in stripped or "record " in stripped):
             # check if immediate previous lines were javadoc
             # We allow annotations in between

             # Scan backwards from i-1 to see if we hit javadoc end or just annotations/empty lines
             j = i - 1
             has_doc = False
             while j >= 0:
                 prev_line = lines[j].strip()
                 if prev_line == "" or prev_line.startswith("@"):
                     j -= 1
                     continue
                 if prev_line.endswith("*/"):
                     has_doc = True
                 break

             if not has_doc:
                 missing_docs.append(f"Class/Interface: {stripped} at line {i+1}")

        # Check for public method
        # public [static] [final] <Type> name(...)
        # excluding constructors (matches class name) but let's just flag all public
        if "public" in stripped and "(" in stripped and ")" in stripped and "{" in stripped and "new " not in stripped and "=" not in stripped:
             # Basic heuristic to avoid fields or other statements

             # Scan backwards
             j = i - 1
             has_doc = False
             while j >= 0:
                 prev_line = lines[j].strip()
                 if prev_line == "" or prev_line.startswith("@"):
                     j -= 1
                     continue
                 if prev_line.endswith("*/"):
                     has_doc = True
                 break

             if not has_doc:
                 missing_docs.append(f"Method: {stripped} at line {i+1}")

    return missing_docs

def main():
    files = find_java_files("src/main/java")
    for file in files:
        missing = check_javadoc(file)
        if missing:
            print(f"File: {file}")
            for m in missing:
                print(f"  - {m}")

if __name__ == "__main__":
    main()
