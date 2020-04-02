/*
A utility class for reading simulation output files.
Primarily for use by those who wish to write code to analyze these data.
This is a pure header implementation. No need to build/link extra libraries.

Copyright 2020 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

#ifndef output_parser_h
#define output_parser_h

#include <string>
#include <vector>
#include <limits>
#include <fstream>
#include <cmath>

namespace n2a
{
    class Column
    {
    public:
        std::string        header;
        int                index;  // If this is a spike raster, then header should convert to an integer.
        std::vector<float> values;
        float              value;  // For most recent row
        int                startRow;
        int                textWidth;
        double             minimum;
        double             maximum;
        double             range;

        Column (const std::string & header)
        :   header (header)
        {
            index     = 0;
            startRow  = 0;
            textWidth = 0;
            minimum   =  std::numeric_limits<double>::infinity ();
            maximum   = -std::numeric_limits<double>::infinity ();
            range     = 0;
        }

        computeStats ()
        {
            for (auto f : values)
            {
                if (std::isinf (f)  ||  std::isnan (f)) continue;
                minimum = std::min (minimum, (double) f);
                maximum = std::max (maximum, (double) f);
            }
            if (std::isinf (maximum))  // There was no good data. If max is infinite, then so is min.
            {
                // Set defensive values.
                range   = 0;
                minimum = 0;
                maximum = 0;
            }
            else
            {
                range = maximum - minimum;
            }
        }

        float get (int row = -1, float defaultValue = 0)
        {
            if (row < 0) return value;
            row -= startRow;
            if (row < 0  ||  row >= values.size ()) return defaultValue;
            return values[row];
        }
    };

    class OutputParser
    {
    public:
        std::vector<Column *> columns;
        std::ifstream *       in;
        bool                  raw;        // Indicates that all column names are empty, likely the result of output() in raw mode.
        bool                  isXycePRN;
        Column *              time;
        bool                  timeFound;  // Indicates that time is a properly-labeled column, rather than a fallback.
        int                   rows;       // Total number of rows successfully read by nextRow()
        float                 defaultValue;

        OutputParser ()
        {
            in           = 0;
            defaultValue = 0;
        }

        ~OutputParser ()
        {
            close ();
        }

        /**
            Use this function in conjunction with nextRow() to read file line-by-line
            without filling memory with more than one row.
        **/
        void open (const std::string & fileName)
        {
            close ();
            in        = new std::ifstream (fileName.c_str ());
            raw       = true;  // Will be negated if any non-empty column name is found.
            isXycePRN = false;
            time      = 0;
            timeFound = false;
            rows      = 0;
        }

        void close ()
        {
            if (in) delete in;
            in = 0;
            for (Column * c : columns) delete c;
            columns.clear ();
        }

        /**
            @return Number of columns found in current row. If zero, then end-of-file
            has been reached or there is an error.
        **/
        int nextRow ()
        {
            if (! in) return 0;
            std::string line;
            while (true)
            {
                getline (*in, line);
                if (! in->good ()) return 0;
                if (line.empty ()) continue;
                if (line.substr (0, 6) == "End of") return 0;  // Don't mistake Xyce final output line as a column header.

                int c = 0;  // Column index
                int start = 0;  // Current position for column scan.
                char l = line[0];
                bool isHeader = (l < '0'  ||  l > '9')  &&  l != '+'  &&  l != '-';
                if (isHeader) raw = false;
                while (true)
                {
                    int length;
                    int next;
                    int pos = line.find_first_of (" \t", start);
                    if (pos == std::string::npos)
                    {
                        length = std::string::npos;
                        next   = std::string::npos;
                    }
                    else
                    {
                        length = pos - start;
                        next   = pos + 1;
                    }

                    // Notice that c can never be greater than column count,
                    // because we always fill in columns as we go.
                    if (isHeader)
                    {
                        if (c == columns.size ()) columns.push_back (new Column (line.substr (start, length)));
                    }
                    else
                    {
                        if (c == columns.size ()) columns.push_back (new Column (""));
                        Column * column = columns[c];
                        if (length == 0)
                        {
                            column->value = defaultValue;
                        }
                        else
                        {
                            column->textWidth = std::max (column->textWidth, length);
                            std::string text = line.substr (start, length);
                            column->value = atof (text.c_str ());
                        }
                    }

                    if (next == std::string::npos) break;
                    c++;
                    start = next;
                }

                if (isHeader)
                {
                    isXycePRN =  columns[0]->header == "Index";
                }
                else
                {
                    rows++;
                    return c;
                }
            }
        }

        /**
            Use this function to read the entire file into memory.
        **/
        void parse (const std::string & fileName, float defaultValue = 0)
        {
            this->defaultValue = defaultValue;
            open (fileName);
            while (int count = nextRow ())
            {
                int c;
                for (c = 0; c < count; c++)
                {
                    Column * column = columns[c];
                    if (column->values.empty ()) column->startRow = rows - 1;
                    column->values.push_back (column->value);
                }
                for (; c < columns.size (); c++)
                {
                    Column * column = columns[c];
                    column->values.push_back (defaultValue);  // Because the structure is not sparse, we must fill out every row.
                }
            }
            if (columns.empty ()) return;

            // If there is a separate columns file, open and parse it.
            std::string columnFileName = fileName + ".columns";
            std::ifstream columnFile (columnFileName.c_str ());
            std::string line;
            for (Column * c : columns)
            {
                getline (columnFile, line);
                if (! columnFile.good ()) break;
                if (c->header.empty ()) c->header = line;
            }

            // Determine time column
            time = columns[0];  // fallback, in case we don't find it by name
            int timeMatch = 0;
            for (Column * c : columns)
            {
                int potentialMatch = 0;
                if      (c->header == "t"   ) potentialMatch = 1;
                else if (c->header == "TIME") potentialMatch = 2;
                else if (c->header == "$t"  ) potentialMatch = 3;
                if (potentialMatch > timeMatch)
                {
                    timeMatch = potentialMatch;
                    time = c;
                    timeFound = true;
                }
            }

            // Get rid of Xyce "Index" column, as it is redundant with row number.
            if (isXycePRN) columns.erase (columns.begin ());
        }

        Column * getColumn (const std::string & columnName)
        {
            for (Column * c : columns) if (c->header == columnName) return c;
            return 0;
        }

        float get (const std::string & columnName, int row = -1)
        {
            Column * c = getColumn (columnName);
            if (c == 0) return defaultValue;
            return c->get (row);
        }

        bool hasData ()
        {
            for (Column * c : columns) if (! c->values.empty ()) return true;
            return false;
        }

        bool hasHeaders ()
        {
            for (Column * c : columns) if (! c->header.empty ()) return true;
            return false;
        }

        void dump (std::ostream & out)
        {
            if (columns.empty ()) return;
            Column * last = columns.back ();

            if (hasHeaders ())
            {
                for (Column * c : columns)
                {
                    out << c->header;
                    if (c == last) out << std::endl;
                    else           out << "\t";
                }
            }

            if (hasData ())
            {
                for (int r = 0; r < rows; r++)
                {
                    for (Column * c : columns)
                    {
                        out << c->get (r);
                        if (c == last) out << std::endl;
                        else           out << "\t";
                    }
                }
            }
        }
    };
}

#endif
