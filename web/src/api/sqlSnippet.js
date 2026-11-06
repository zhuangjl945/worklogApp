import http from './http'

export async function sqlSnippetList(params) {
  return await http.get('/sql-snippets', { params })
}

export async function sqlSnippetCategories() {
  return await http.get('/sql-snippets/categories')
}

export async function sqlSnippetCreate(payload) {
  return await http.post('/sql-snippets', payload)
}

export async function sqlSnippetUpdate(id, payload) {
  return await http.put('/sql-snippets/' + id, payload)
}

export async function sqlSnippetDelete(id) {
  return await http.delete('/sql-snippets/' + id)
}
