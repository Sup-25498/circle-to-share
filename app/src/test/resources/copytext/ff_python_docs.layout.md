raises an IndexError if tne list is empty or tne index is outside tne list

range.

## list.clear()

Remove all items from the list. Similar to del a[ :].

## list.index(value[, start[, stop]])

Return zero-based index of the first occurrence of value in the list. Raises a ValueError if there is no such item.

The optional arguments start and end are interpreted as in the slice notation and are used to limit the search to a particular subsequence of the list. The returned index is computed relative to the beginning of the full sequence rather than the start argument.

## list.count(value,

Return the number of times value appears in the list.

### list.sort(*, key=None, reverse=False)

Sort the items of the list in place (the arguments can be used for sort customization, see sorted() for their explanation).

## list.reverse()

Reverse the elements of the list in place.

## list.copy()

Return a shallow copy of the list. Similar to a[ : ].

An example that uses most of the list methods:

```
fruits = ['orange'  'apple'  'pear'  ' banana   'kiwi'
fruits.count('apple')
```

```
>>> fruits.count('tangerine')
```

### fruits.index('banana'

### fruits.index('banana 4 # Find next banana starting

```
>>> fruits.reverse()
>>> fruits
[ 'banana   'apple'  'kiwi   banana    pear   'apple'   ora
>>> fruits.append('grape')
>>> fruits
['banana'   'apple' 'kiwi'  'banana'   pear   'apple'   orà
    fruits.sort()
>>> fruits
['apple'  'apple'  ' banana   banana   grape   'kiwi    o
>>> fruits.pop()
'pear'
```

You might have noticed that methods like insert, remove or sort that only
