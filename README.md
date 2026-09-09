A WML Preprocessor/Parser/LSP Server and multitool.

# Building
Requires Java 21. Run `mvn package`. The final JAR file will be in `jar/wml.jar`.

# Command line options
```bash
Usage: wml [OPTIONS] [INPUT]

Arguments:
  INPUT   Path to the main input file or folder (not needed in LSP mode -s)

Options:

  Server / mode:
    -server, -s                               Start as WML LSP server
    -fastMode, -fm                            Fast mode: skip macro expansion & parsing. Auto-enabled for -df, -gmr, -s.


  Paths:
    -datadir <path>                           Absolute path to Wesnoth data dir (or env: WESNOTH_DATA)
    -userdatadir <path>                       Absolute path to Wesnoth userdata dir (or env: WESNOTH_USERDATA)
    -i, -include <path>                       Preprocess file/folder to collect macro definitions. Repeatable.
    -o, -output <path>                        Write output to file (default: stdout)


  Defines:
    -define, -d <NAME> <BODY>                 Define macro before parsing. Repeatable.


  Logging:
    -log-parse, -log-p                        Parser debug logs (= -log-level debug)
    -warn-parse, -warn-p                      Parser warnings only (= -log-level warn)
    -log-level <severe|warn|info|debug|off>   Set log level explicitly
    -color <true|false>                       Toggle ANSI color in logs (default: true)


  Parsing:
    -parse <true|false>                       Toggle parsing preprocessed output (default: true)


  Data extraction / queries:
    -l, -list-files                           Print tree of preprocessed file names
    -df, -definitions                         List all macro definitions
    -q, -query <expr>                         XPath-style WML query. Repeatable. Comma shorthand: -q tag/a,b,c expands to three queries. Multiple queries emit one row per shared top-level tag.
    -eut, -extract-unit-type <path>           Extract unit type data to CSV
    -gmr, -generate-macro-ref <path>          Generate HTML macro reference


  Help / version:
    -h, -help, -?                             Print this help
    -v, -version                              Print version information

```

### Supported LSP features:
* Hover, Go To Definition and References for WML macro calls.
* Hover and Go To Definition for Unit Types.
* Completion for macro directive, macro calls and Unit Types.
* Hover info for WML paths including AnimationWML. Show image preview if path is image.
* Completion for tag names. Shows help page link for tag names on hover.
* Preliminary Wesnoth path autocomplete. (Triggered by '/')
* Inlay Hints for position macro call arguments.
* Symbol table for macro definitions.

### Supported features
* Detailed colored logging, including files preocessed/macros found and others in debug mode
* Macro reference generation
* Custom WML queries into WML codebases
* Unit Type data extraction (WIP)

### Queries

**Single query** — prints one line per matched value. The default format is `{VALUE}`; customize with `-Dquery.format`.

**Multi-query rows** — two or more `-q` options that share a top-level tag emit one result row per occurrence of that tag. Both of these are equivalent:

```bash
-q "//unit_type/id" -q "//unit_type/name" -q "//unit_type/level"
-q "//unit_type/id,name,level"
```

The comma shorthand expands inside the query parser — the prefix up to the last `/` before the first comma is shared, and each comma-separated field becomes its own column.

The default row format is `{VALUE1}, {VALUE2}, …` (auto-generated from column count). Customize with `-Dquery.format`, using `{VALUE1}` through `{VALUEn}` as placeholders:

```bash
-Dquery.format='{VALUE1} ({VALUE2})'
```

Repeated matches within one column are joined with `|`; missing values are empty strings.

### Usage
* **VSCode**: Use the extension from [here](https://github.com/babaissarkar/wml-extension).
* **Kate**: Download the server JAR, install Java runtime, then use this config in **Settings > Configure Kate > LSP Client > User Server settings**.
Adjust paths as needed. Append the `wml` section to your `servers` if you have other stuff there.
```json
{
    "servers": {
        "wml": {
            "command": ["/usr/bin/java","-jar","/path/to/wml.jar","-s","-datadir","/path/to/wesnoth/data","-userdatadir","/path/to/wesnoth/user/data","-include","/path/to/wesnoth/datadir/core/macros","-include","/path/to/wesnoth/datadir/core/units.cfg"],
            "useWorkspace": true,
            "rootIndicationFileNames": ["_main.cfg"],
            "highlightingModeRegex": "Wesnoth"
        }
   }
}
```

Note: this is still very much a prototype. Please be forgiving and report any errors you come across. A log is usually available in Output tab in VSCode under WML LSP Server category, or the Output tab in Kate.
