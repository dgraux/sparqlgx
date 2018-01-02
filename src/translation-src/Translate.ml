open Sparql ;;
open Reorder ;;
open Algebra ;;

let print_query =
  (* let print_list_tp l = *)
  (*   let rec foo = function *)
  (*     | [] -> failwith "Empty list of TP" *)
  (*     | [a] -> print_tp a *)
  (*     | a::q -> print_tp a ; foo q *)
  (*   in *)
  (*   failwith "not supported"  *)
  (*   (\* match optim with *\) *)
  (*   (\* | 0 -> foo (List.rev l) *\) *)
  (*   (\* | 1 -> foo (Reorder.no_cartesian l) *\) *)
  (*   (\* | 2 -> foo (Reorder.reorder l) *\) *)
  (*   (\* | _ -> foo (Reorder.no_cartesian l) *\) *)
  (* in *)

  (* let print_opt  = function *)
  (*   | (a,[]) -> print_list_tp a *)
  (*   | (a,b) -> print_list_tp a ; print_list_tp b *)
  (* in *)
  
()

let rec print_query distinguished modifiers optim stmt =

  let rec get_tp = function
    | Readfile2(p) -> ["p",p]
    | Readfile3 -> []
    | Filter(Equal(Var col,Atom value),term) -> (col,value)::get_tp term 
    | Rename(col,value,term) -> (col,value)::get_tp term
    | _ -> failwith ("Unrecognized pattern @ "^__LOC__)
  in
  
  let rec print_toplevel t =

    let rec foo bc ps t =
      let s = "  "^ps in
      let size = string_of_int (Plan.get_size t) in
      match t with 
      | Empty -> ""
      | Union(a,b) ->
         "\n"^ps^"{"^foo bc s a^"}\n"^ps^"UNION"^ps^"\n"^ps^"{"^foo bc s b^"}"^" //"^size
      | Join (a,b) ->
         "\n"^ps^"{"^foo bc s a^foo bc s b^"\n"^ps^"}"^" //"^size
      | JoinWithBroadcast (a,b) ->
         "\n"^ps^"{"^foo bc s a^foo bc s b^"\n"^ps^"}"^" //"^size
      | LeftJoin(a,b) ->
         "\n"^ps^"{"^foo bc s a^"\n"^ps^"} OPTIONAL {"^foo bc s b^"\n"^ps^"}"^" //"^size
      | Keep(_,l) ->
         let tp = get_tp l in
         "\n"^ps^(List.assoc "s" tp)^" "^(List.assoc "p" tp)^" "^(List.assoc "o" tp)^" ."^" //"^size
      | Rename(c,v,t) -> foo bc ps (Keep([],(Rename (c,v,t))))
      | Distinct a | Order (_,a) -> foo bc ps a
      | Broadcast(v,a,b) -> foo ((v,a)::bc) ps b
      | FilterWithBroadcast(a,v,l) -> foo bc ps (Join(a,List.assoc v bc))
      | _ -> failwith ("Unrecognized pattern @ "^__LOC__)
    in

    print_string (match t with | Keep(_,l) | l -> foo [] "" l) 
  in

  let rec print_list_order = function
    | [] -> ()
    | (x,b)::q ->
       print_string (if b then " ASC(" else " DESC(") ;
       print_string x;
       print_string (if q != [] then  ")," else ")") ;
       print_list_order q
  in

  let rec print_modifiers stmt = function
    | [] ->
       begin
        List.iter (Printf.printf "%s ") distinguished ;
        print_string "\nWHERE {" ;
        print_toplevel stmt ;
        print_string "}\n" ;
       end
    | OrderBy(l)::q ->
       print_modifiers stmt q ;
       if l <> [] then
       begin
        print_string "Order By " ;
        print_list_order l ;
       end
    | Distinct::q ->
       begin
        print_string "DISTINCT " ;
        print_modifiers stmt q ;
       end
  in


  print_string "SELECT ";
  print_modifiers stmt modifiers 
  


  
let translate distinguished modifiers vertical optim stmt =
  
  let translate_el (base,cols) = function
    | Exact(v),name -> (Filter((Equal(Var name,Atom v)),base),cols)
    | Variable(v),name ->
       if List.mem v cols
       then Filter(Equal (Var name, Var v),base),cols
       else (Rename(name,v,base),v::cols)
  in

  let translate_tp = function
    | s,Exact(p),o when vertical -> Keep(list_var [s;o],fst (List.fold_left translate_el (Readfile2(p),[]) [s,"s";o,"o"]))
    | s,p,o -> Keep(list_var [s;p;o],fst (List.fold_left translate_el (Readfile3,[]) [s,"s";p,"p";o,"o"]))
  in

  let vars l =
    let rec foo = function
      | [] -> []
      | (s,p,o)::q -> [s; p; o]@foo q
    in
      
    let rec bar l = function
      | Variable(x) -> if List.mem x l then l else x::l
      | Exact(x) -> l
    in
    l |> foo |> List.fold_left bar []
  in

  let not_blank x = x.[0] <> '_' in 
    
  
  let translate_list_tp l =
    let reordered_bgp =
      match optim with
      | 0 -> Reorder.no_optim translate_tp (List.rev l)
      | 1 -> Reorder.no_cartesian translate_tp l
      | 2 -> Reorder.reorder  translate_tp l
      | 3 -> Reorder.full_stat translate_tp l
      | _ -> Reorder.no_cartesian  translate_tp l
    in
    if vars l |> List.for_all not_blank
    then reordered_bgp
    else Keep(vars l |> List.filter not_blank, reordered_bgp)
  in

        

  
  let translate_id_var = function
    | Variable v -> Var v
    | Exact v -> Atom v
  in
  let translate_filter = function
    | Sparql.Equal(a,b) -> Equal(translate_id_var a,translate_id_var b)
    | Sparql.Less(a,b) -> Less(translate_id_var a,translate_id_var b)
    | Sparql.Match(a,b) -> Match(translate_id_var a,translate_id_var b)
  in
  
  let rec translate_toplevel = function
    | Sparql.Union(a,b) -> Union(translate_toplevel a,translate_toplevel b)
    | Sparql.Optional(a,b) -> LeftJoin(translate_toplevel a,translate_toplevel b)
    | Sparql.BGP(a) -> translate_list_tp a
    | Sparql.Join(a,b) -> Join(translate_toplevel a,translate_toplevel  b)
    | Sparql.Filter(a,b) -> Filter(translate_filter b,translate_toplevel a)
  in

  let rec add_modifiers t = function
    | [] -> t
    | OrderBy(l)::q -> Order(l,add_modifiers t q)
    | Distinct::q -> Distinct(add_modifiers t q)
  in
  
  add_modifiers (match distinguished with
   | ["*"] -> translate_toplevel stmt
   | _ ->  Keep(distinguished,  translate_toplevel stmt))
   modifiers 
  
  (* let _ = *)
(*    print_algebra (Union(Join ( *)
(*                      Keep(["pers"],Rename("s","pers",Filter("o","21",Readfile2("age")))), *)
(*                      Keep(["gender";"pers"],Rename("o","gender",Rename("s","pers",Readfile2("gender")))) *)
(*                       ), *)
(*                       Keep(["a"],Rename("s","a",Readfile2("age") )) *)
(*                       )) *)
  
  
