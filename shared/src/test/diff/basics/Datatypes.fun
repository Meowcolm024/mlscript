
:p
data type Boolean of True, False
//│ Parsed: data type Boolean of True; False;;
//│ Desugared: type alias Boolean = True[] | False[]
//│ Desugared: class True: {}
//│ Desugared: class False: {}
//│ Desugared: def True: [] -> True[]
//│ Desugared: def False: [] -> False[]
//│ Defined type alias Boolean
//│ Defined class True
//│ Defined class False
//│ True: true
//│ False: false

:e
Boolean
//│ ╔══[ERROR] identifier not found: Boolean
//│ ║  l.17: 	Boolean
//│ ╙──      	^^^^^^^
//│ res: error

:p
:e
data type Bool2 of True2 & False2
//│ Parsed: data type Bool2 of ((& True2) False2);;
//│ Desugared: type alias Bool2 = &[True2, False2]
//│ Desugared: class &[True2, False2]: {False2: False2, True2: True2}
//│ Desugared: def &: [True2, False2] -> True2 -> False2 -> &[True2, False2]
//│ ╔══[ERROR] type identifier not found: True2
//│ ║  l.25: 	data type Bool2 of True2 & False2
//│ ╙──      	                   ^^^^^
//│ ╔══[ERROR] type identifier not found: False2
//│ ║  l.25: 	data type Bool2 of True2 & False2
//│ ╙──      	                           ^^^^^^
//│ ╔══[ERROR] Type names must start with a capital letter
//│ ║  l.25: 	data type Bool2 of True2 & False2
//│ ╙──      	                         ^
//│ ╔══[ERROR] Field identifiers must start with a small letter
//│ ║  l.25: 	data type Bool2 of True2 & False2
//│ ╙──      	                           ^^^^^^
//│ ╔══[ERROR] Field identifiers must start with a small letter
//│ ║  l.25: 	data type Bool2 of True2 & False2
//│ ╙──      	                   ^^^^^
//│ Defined type alias Bool2
//│ Defined class &
//│ &: 'a -> 'b -> &['a, 'b]

data type Bool3 of
  True3; False3
//│ Defined type alias Bool3
//│ Defined class True3
//│ Defined class False3
//│ True3: True3
//│ False3: False3

data type Bool4 of
  True4
  False4
//│ Defined type alias Bool4
//│ Defined class True4
//│ Defined class False4
//│ True4: True4
//│ False4: False4

:e
Boolean
//│ ╔══[ERROR] identifier not found: Boolean
//│ ║  l.67: 	Boolean
//│ ╙──      	^^^^^^^
//│ res: error

True
//│ res: true

:e // TODO support types on RHS of `as`
True as Boolean
True : Boolean
//│ ╔══[ERROR] identifier not found: Boolean
//│ ║  l.77: 	True as Boolean
//│ ╙──      	        ^^^^^^^
//│ res: error
//│ ╔══[ERROR] identifier not found: Boolean
//│ ║  l.78: 	True : Boolean
//│ ╙──      	       ^^^^^^^
//│ res: (True: error,)

:e // Maybe we shouldn't interpret capitalized identifiers as field names...
True : Boolean
//│ ╔══[ERROR] identifier not found: Boolean
//│ ║  l.89: 	True : Boolean
//│ ╙──      	       ^^^^^^^
//│ res: (True: error,)

:pe
(True) : Boolean
//│ /!\ Parse error: Expected end-of-input:1:8, found ": Boolean\n" at l.96:8: (True) : Boolean


// TODO treat the ending curly-blocks as bodies (not params)?
// data type List of
//   Nil { T: Nothing }
//   Cons head tail { T: head | tail.T }

// TODO also try the one-line version:
// data type List of Nil { T: Nothing }, Cons head tail { T: head | tail.T }

:p
data type List a of
  Nil
  Cons (head: a) (tail: List a)
//│ Parsed: data type (List a) of Nil; ((Cons ((head: a,);)) ((tail: (List a),);));;
//│ Desugared: type alias List[a] = Nil[a] | Cons[a]
//│ Desugared: class Nil[a]: {}
//│ Desugared: class Cons[a]: {head: a, tail: List[a]}
//│ Desugared: def Nil: [a] -> Nil[a]
//│ Desugared: def Cons: [a] -> (head: a,) -> (tail: List[a],) -> Cons[a]
//│ Defined type alias List
//│ Defined class Nil
//│ Defined class Cons
//│ Nil: Nil['a]
//│ Cons: (head: 'a,) -> (tail: (Cons['a] with {tail: 'b}) | Nil['a] as 'b,) -> ((Cons['a] with {tail: 'd | 'c | Nil['a]}) as 'c)

// TODO interpret as free type variable?
:p
data type Ls of LsA a
//│ Parsed: data type Ls of (LsA a);;
//│ Desugared: type alias Ls = LsA[a]
//│ Desugared: class LsA[a]: {a: a}
//│ Desugared: def LsA: [a] -> a -> LsA[a]
//│ ╔══[ERROR] type identifier not found: a
//│ ║  l.126: 	data type Ls of LsA a
//│ ╙──       	                    ^
//│ Defined type alias Ls
//│ Defined class LsA
//│ LsA: 'a -> LsA['a]

:p
data type Ls2 of LsA2 `a
//│ Parsed: data type Ls2 of (LsA2 `a);;
//│ Desugared: type alias Ls2 = LsA2[]
//│ Desugared: class LsA2: {`a: 'a}
//│ Desugared: def LsA2: [] -> 'a -> LsA2[]
//│ Defined type alias Ls2
//│ Defined class LsA2
//│ LsA2: anything -> (LsA2 with {`a: nothing})

Nil
Cons
Cons 1
Cons 2 Nil
Cons 1 (Cons 2 Nil)
//│ res: Nil['a]
//│ res: (head: 'a,) -> (tail: (Cons['a] with {tail: 'b}) | Nil['a] as 'b,) -> ((Cons['a] with {tail: 'd | 'c | Nil['a]}) as 'c)
//│ res: (tail: (Cons[1 | 'b .. 'b] with {tail: 'a}) | Nil[1 | 'b .. 'b] as 'a,) -> ((Cons['b .. 1 | 'b] with {tail: 'd | 'c | Nil['b .. 1 | 'b]}) as 'c)
//│ res: (Cons['b .. 2 | 'b] with {tail: 'c | 'a | Nil['b .. 2 | 'b]}) as 'a
//│ res: (Cons['b .. 1 | 2 | 'b] with {tail: 'c | 'a | Nil['b .. 1 | 2 | 'b]}) as 'a

(Cons 3 Nil).head
succ (Cons 3 Nil).head
not (Cons false Nil).head
//│ res: 3
//│ res: int
//│ res: bool

:e
not (Cons 42 Nil).head
//│ ╔══[ERROR] Type mismatch in application:
//│ ║  l.167: 	not (Cons 42 Nil).head
//│ ║         	^^^^^^^^^^^^^^^^^^^^^^
//│ ╟── expression of type `42` does not match type `bool`
//│ ║  l.167: 	not (Cons 42 Nil).head
//│ ║         	          ^^
//│ ╟── but it flows into field selection with expected type `bool`
//│ ║  l.167: 	not (Cons 42 Nil).head
//│ ╙──       	                 ^^^^^
//│ res: bool | error

:e
(Cons 4).head
//│ ╔══[ERROR] Type mismatch in field selection:
//│ ║  l.180: 	(Cons 4).head
//│ ║         	        ^^^^^
//│ ╟── expression of type `(tail: List[?a],) -> Cons[?a]` does not have field 'head'
//│ ║  l.109: 	data type List a of
//│ ║         	               ^^^^
//│ ║  l.110: 	  Nil
//│ ║         	^^^^^
//│ ║  l.111: 	  Cons (head: a) (tail: List a)
//│ ║         	^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
//│ ╟── but it flows into receiver with expected type `{head: ?b}`
//│ ║  l.180: 	(Cons 4).head
//│ ╙──       	^^^^^^^^
//│ res: error

:e
Cons 1 2
//│ ╔══[ERROR] Type mismatch in application:
//│ ║  l.197: 	Cons 1 2
//│ ║         	^^^^^^^^
//│ ╟── expression of type `2` does not match type `Nil[?a] | Cons[?a]`
//│ ║  l.197: 	Cons 1 2
//│ ║         	       ^
//│ ╟── Note: constraint arises from union type:
//│ ║  l.109: 	data type List a of
//│ ║         	               ^
//│ ╟── from applied type reference:
//│ ║  l.111: 	  Cons (head: a) (tail: List a)
//│ ╙──       	                        ^^^^^^
//│ res: ((Cons['b .. 1 | 'b] with {tail: 'c | 'a | Nil['b .. 1 | 'b]}) as 'a) | error

// TODO Allow method/field defintions in the same file (lose the let?):
:e
let List.head = () // ...
//│ ╔══[ERROR] Unsupported pattern shape
//│ ║  l.214: 	let List.head = () // ...
//│ ╙──       	        ^^^^^
//│ <error>: ()


