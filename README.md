# C/C++ Coverage for CLion

![alt text][logo]

[logo]:https://i.imgur.com/PNvQs9j.png "View in the Editor"

## Content
1. [Getting started](#getting-started)
   1. [Tools](#tools)
   2. [Compiling with Coverage](#compiling-with-coverage)
   3. [Running](#running)
2. [Differences in compilers](#differences-in-compilers)
3. [Known Issues/TODOs](#known-issuestodos)

## Getting Started

### Tools

To get coverage you need one of the following compilers: Clang 5 or later, GCC 6 or later.
Recommended are Clang 6 onwards and GCC 9 onwards. GCC 6 to 8 are not recommended for larger projects and cannot gather 
branch coverage. To see why refer to [Differences in compilers](#differences-in-compilers). Both of them use very different ways to gather coverage.
GCC ships a tool called gcov while clang requires llvm-cov and llvm-profdata. Make sure the tools versions matches your 
compilers version. When the plugin sees an empty path for either of the tools it will attempt to guess the correct paths
based on C and C++ compiler paths and prefixes and suffixes in their name. Optionally One can specify a demangler when 
using Clang to demangle C++ symbols. This is not required with GCC as gcov has a built in demangler.
You can specify your gcov and llvm-cov per toolchain by going into the settings menu under `Language & Frameworks -> C/C++ Coverage`.
There you can also configure different settings related to processing coverage

### Compiling with Coverage

To compile with coverage each of the two compilers require different flags. In the case of GCC we need the `--coverage` flag
for the compiler. On some platforms one needs to explicitly link against `gcov`. For clang we need `-fprofile-instr-generate -fcoverage-mapping`
for compiler flags and `-fprofile-instr-generate` for linker flags. On some platform one may also need to explicitly link 
again the libclang_rt.profile-\<ARCH\>. An example cmake sections is hown here:
```cmake
if ("${CMAKE_CXX_COMPILER_ID}" STREQUAL "Clang")
    add_compile_options(-fprofile-instr-generate -fcoverage-mapping)
    add_link_options(-fprofile-instr-generate)
    #Uncomment in case of linker errors
    #link_libraries(clang_rt.profile-x86_64)
elseif ("${CMAKE_CXX_COMPILER_ID}" STREQUAL "GNU")
    add_compile_options(--coverage)
    #Uncomment in case of linker errors
    #link_libraries(gcov)
endif ()
```

Alternatively one can enable in the settings to not use auto flag detection. Instead a 'Run Coverage' button will appear
with which one can explicitly choose to gather coverage data

#### Note:
When using Clang 8 or older on Windows you'll most likely get a linker error due to the symbol `lprofGetHostName` missing.
This is due to a bug inside llvm's compiler-rt. To circumvent this add the following lines of code to one of your files:
```cpp
#ifdef __clang__
#if _WIN32 && __clang_major__ == 8 && __clang_minor__ == 0 &&                  \
    __clang_patchlevel__ == 0

#include <windows.h>

extern "C" int lprofGetHostName(char *Name, int Len) {
    WCHAR Buffer[128];
    DWORD BufferSize = sizeof(Buffer);
    BOOL Result =
        GetComputerNameExW(ComputerNameDnsFullyQualified, Buffer, &BufferSize);
    if (!Result)
        return -1;
    if (WideCharToMultiByte(CP_UTF8, 0, Buffer, -1, Name, Len, nullptr,
                            nullptr) == 0)
        return -1;
    return 0;
}

#endif
#endif

```
I recommend putting it into the main file of your testing framework eg.

### Running

Similar to how sanitizers work in CLion, coverage is automatically gathered when the right compiler flags are detected.
After your executable has terminated a modal dialog will open telling you that coverage is being 
gathered and prohibiting you from editing the source code. As soon as it finishes the tool window
on the right will open and display your statistics. You can double click either on a file or a
function to navigate to said symbol or you can click on the header above to sort by a metric. By default
only source files inside of your project will be displayed. By checking the checkbox on the top right every file compiled 
into your project will be shown. Using the clear button will clear all editors and the tool window.

## Differences in compilers

This plugin implements 3 different coverage gathers. Clang 5 onwards, GCC 9 onwards and GCC 6 to 8.
GCC 6 to 8 is not recommended and cannot produce branch coverage like the other two.
The most powerful of these implementations is Clang.
 
Clang doesn't use line coverage but instead uses region coverage and has no branch coverage on its own. A region is a
 block of code that has the same execution count and is specified from start to end 
character. This precise data allows this plugin to generate branch coverage for Clang via postprocessing by making a query 
into the CLion parser structure and comparing the execution counts of neighbouring regions. Region coverage also
allows checking if operands of boolean operators that short circuit have been evaluated or not. 

GCC 9 and onwards on the other hand produces line and branch coverage. The problem with this approach is
that the data is less precise and has major problems when there are multiple statements on a single line.
Branch coverage is also given per line and the plugin needs to make a query into the line to match up the branches specified
by gcov to the statements in the source code. Another issue is that GCC generates a branch for every expression that may
throw an exception. As 99% of all expressions that could throw an exception never do and showing such branches would generate 
incredible amount of noise in the editor the plugin filters them out.

Previous versions of gcc generated very similar information as GCC 9. Branch coverage however does not carry information 
as to where a branch comes from and as all newer versions of gcc implement the GCC 9 format or newer, I decided not 
to try and implement branch coverage for GCC 6 to 8. Line coverage however should work as intended. Please note that 
gcov versions 6 to 8 crash easily on large projects.

## Known Issues/TODOs

* Due to the plugin needing the parser structure of source files coverage gathering is paused and later resumed if 
indexing is in progress
* This plugin is untested on Mac OS-X. It should work as long as the toolchains are setup correctly. One thing to watch 
out for is that gcc, g++ and gcov installed by XCode are **NOT** GCC but actually clang,clang++ and a gcov like implementation 
by the LLVM project. This version of gcov will **NOT WORK** as it does not implement the same command line arguments as 
real gcov. Please install another toolchain or find a llvm-cov version suitable for your version of clang.
* Currently a new function is generated for each type in a template instantiations. if constexpr
also has weird data and in general data in such functions may be less accurate. Future versions will 
try to address this issue and possibly even parse the function name in order to collapse different instantiations of the 
same function
* Remote Machine is currently in WIP and requires some restructuring of the Architecture. WSL is supported