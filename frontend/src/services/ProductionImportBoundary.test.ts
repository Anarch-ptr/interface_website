/// <reference types="node" />
import { readFileSync, existsSync } from 'node:fs'
import { dirname, resolve, relative } from 'node:path'
import ts from 'typescript'
import { expect, it } from 'vitest'

it('keeps mock generators and test fixtures outside the main production import graph', () => {
  const root = resolve(process.cwd(), 'src')
  const visited = new Set<string>()
  function visit(path: string) {
    if (visited.has(path)) return
    visited.add(path)
    expect(relative(root, path)).not.toMatch(/MockFilamentDataSource|(^|[/\\])test[/\\]/)
    const source = ts.createSourceFile(path, readFileSync(path, 'utf8'), ts.ScriptTarget.Latest, true)
    function walk(node: ts.Node) {
      let module: string | undefined
      if ((ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) && node.moduleSpecifier && ts.isStringLiteral(node.moduleSpecifier)) module = node.moduleSpecifier.text
      if (ts.isCallExpression(node) && node.arguments.length && ts.isStringLiteral(node.arguments[0]) &&
        (node.expression.kind === ts.SyntaxKind.ImportKeyword || node.expression.getText(source) === 'require')) module = node.arguments[0].text
      if (module?.startsWith('.')) {
        const target = resolve(dirname(path), module)
        const dependency = ['', '.ts', '.tsx', '/index.ts', '/index.tsx'].map(extension => target + extension).find(existsSync)
        if (dependency && /\.tsx?$/.test(dependency)) visit(dependency)
      }
      ts.forEachChild(node, walk)
    }
    walk(source)
  }
  visit(resolve(root, 'main.tsx'))
})
