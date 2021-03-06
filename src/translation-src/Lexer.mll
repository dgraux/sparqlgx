(* Tokenizer for Sparql *)
{
  open Parser

  exception Lexing_error of string

  let kwd_tbl = [
      "SELECT", SELECT;
      "WHERE", WHERE;
      "FILTER", FILTER;
      "PREFIX", PREFIX;
      "UNION", UNION;
      "OPTIONAL",OPTIONAL;
      "ORDER",ORDER;
      "REGEX",REGEX;
      "BY",BY;
      "ASC",ASC;
      "DESC",DESC;
      "DISTINCT",DISTINCT]
  let id_or_kwd s = try List.assoc (String.uppercase s) kwd_tbl with _ -> IDENT s
  let line = ref 1
  let newline () = incr line
}

let number = ['0'-'9']['0'-'9']*
let alphanum = (['a'-'z' 'A'-'Z' '0'-'9' '_' '/' '-' '#' '~'] | (['\x80'-'\xff']+['\x00'-'\x7f'])) (['a'-'z' 'A'-'Z' '0'-'9' '_' '/' '-' '#' '~' '.' ] | (['\x80'-'\xff']+['\x00'-'\x7f']) )*
let var = ['?' '$']['a'-'z' 'A'-'Z' '0'-'9' '_']*
let space = ' ' | '\t'
		    
rule next_token = parse
  | '\n'
      { newline () ; next_token lexbuf }
  | space+
      { next_token lexbuf }
  | var as s { VAR(s) }
  | number as id {NUMBER id}
  | alphanum as id { id_or_kwd id }
  | '"' { QUOTE }
  | '{'     { LEFTBRACKET }
  | '}'     { RIGHTBRACKET }
  | '('     { LEFTPAR }
  | ')'     { RIGHTPAR }
  | '<'     { LEFTPROG }
  | '>'     { RIGHTPROG }
  | ':'     { COLON }
  | '.'     { POINT }
  | ','     { COMMA }
  | '='     { EQUAL }
  | '*'     { JOKER }
  | eof     { EOF }
  | _ as c  { raise (Lexing_error ("illegal character: '" ^ String.make 1 c^"' line "^(string_of_int (!line)))) }

{}


