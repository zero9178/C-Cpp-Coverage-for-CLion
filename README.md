# GCoverage4CLion
Get coverage data in CLion using GCoverage

This plugin allows you to generate coverage data and display it in CLion, both as coverage percentage per file and function, as well as show you inside the editor if a line has been executed and how many times. This plugin works and was tested with GCC (MinGW specifically) but should also work with clang. Requirement for getting coverage data is that one must compile with following flags `"-fprofile-arcs -ftest-coverage -g -O0"`. Afterwards run using the coverage icon to the left of the stop process button. As soon as your process terminates a table should then appear showing you the coverage data of each file. You can expand the file to show coverage data of each function. Additionally you can toggle to turn on or off if you want to include coverage data of non Project files. Here you can also enable or disable if you want to show Line Markers inside the Editor.

![alt text][logo]

[logo]:https://imgur.com/Ug47x15.png "View in the Editor"
