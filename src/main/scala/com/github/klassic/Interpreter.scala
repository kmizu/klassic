package com.github.klassic

import scala.collection.JavaConverters._
import java.io.{BufferedReader, File, FileInputStream, InputStreamReader}
import java.lang.reflect.{Constructor, Method}
import java.util

import klassic.runtime._
import com.github.klassic.AST._
import com.github.klassic.Type._
import com.github.klassic.TypedAST.{FunctionLiteral, ValueNode}
import klassic.runtime.{AssertionError, NotImplementedError}

/**
 * @author Kota Mizushima
 */
class Interpreter {evaluator =>
  val typer = new Typer
  val rewriter = new SyntaxRewriter
  def reportError(message: String): Nothing = {
    throw InterpreterException(message)
  }

  def findMethod(self: AnyRef, name: String, params: Array[Value]): MethodSearchResult = {
    val selfClass = self.getClass
    val nameMatchedMethods = selfClass.getMethods.filter {
      _.getName == name
    }
    nameMatchedMethods.find { m =>
      val parameterCountMatches = m.getParameterCount == params.length
      val parameterTypes = Value.classesOfValues(params)
      val parameterTypesMatches = (m.getParameterTypes zip parameterTypes).forall{ case (arg, param) =>
        arg.isAssignableFrom(param)
      }
      parameterCountMatches && parameterTypesMatches
    }.map{m =>
      m.setAccessible(true)
      UnboxedVersionMethodFound(m)
    }.orElse({
      nameMatchedMethods.find{m =>
        val parameterCountMatches = m.getParameterCount == params.length
        val boxedParameterTypes = Value.boxedClassesOfValues(params)
        val boxedParameterTypesMatches = (m.getParameterTypes zip boxedParameterTypes).forall{ case (arg, param) =>
          arg.isAssignableFrom(param)
        }
        parameterCountMatches && boxedParameterTypesMatches
      }
    }.map{m =>
      m.setAccessible(true)
      BoxedVersionMethodFound(m)
    }).getOrElse(NoMethodFound)
  }

  def findConstructor(target: Class[_], params: Array[Value]): ConstructorSearchResult = {
    val constructors = target.getConstructors
    constructors.find{c =>
      val parameterCountMatches = c.getParameterCount == params.length
      val unboxedParameterTypes = Value.classesOfValues(params)
      val parameterTypesMatches  = (c.getParameterTypes zip unboxedParameterTypes).forall{ case (arg, param) =>
        arg.isAssignableFrom(param)
      }
      parameterCountMatches && parameterTypesMatches
    }.map{c =>
      UnboxedVersionConstructorFound(c)
    }.orElse({
      constructors.find{c =>
        val parameterCountMatches = c.getParameterCount == params.length
        val boxedParameterTypes = Value.boxedClassesOfValues(params)
        val parameterTypesMatches  = (c.getParameterTypes zip boxedParameterTypes).forall{ case (arg, param) =>
          arg.isAssignableFrom(param)
        }
        parameterCountMatches && parameterTypesMatches
      }
    }.map { c =>
      BoxedVersionConstructorFound(c)
    }).getOrElse(NoConstructorFound)
  }

  object BuiltinEnvironment extends Environment(None) {
    define("substring"){ case List(ObjectValue(s:String), begin: BoxedInt, end: BoxedInt) =>
      ObjectValue(s.substring(begin.value, end.value))
    }
    define("at") { case List(ObjectValue(s:String), index: BoxedInt) =>
      ObjectValue(s.substring(index.value, index.value + 1))
    }
    define("matches") { case List(ObjectValue(s: String), ObjectValue(regex: String)) =>
      BoxedBoolean(s.matches(regex))
    }

    define("thread") { case List(fun: FunctionValue) =>
      new Thread({() =>
          val env = new Environment(fun.environment)
          evaluator.evaluate(TypedAST.FunctionCall(DynamicType, NoLocation, fun.value, Nil), env)
      }).start()
      UnitValue
    }

    define("println") { case List(param) =>
      println(param)
      param
    }
    define("stopwatch") { case List(fun: FunctionValue) =>
      val env = new Environment(fun.environment)
      val start = System.currentTimeMillis()
      evaluator.evaluate(TypedAST.FunctionCall(DynamicType, NoLocation, fun.value, List()), env)
      val end = System.currentTimeMillis()
      BoxedInt((end - start).toInt)
    }
    define("sleep"){ case List(milliseconds: BoxedInt) =>
      Thread.sleep(milliseconds.value)
      UnitValue
    }

    define("map") { case List(ObjectValue(list: java.util.List[_])) =>
      NativeFunctionValue{
        case List(fun: FunctionValue) =>
          val newList = new java.util.ArrayList[Any]
          val env = new Environment(fun.environment)
          var i = 0
          while(i < list.size()) {
            val param: Value = Value.toKlassic(list.get(i).asInstanceOf[AnyRef])
            val result: Value = performFunctionInternal(fun.value, List(ValueNode(param)), env)
            newList.add(Value.fromKlassic(result))
            i += 1
          }
          ObjectValue(newList)
      }
    }

    define("assert") { case List(BoxedBoolean(condition)) =>
        if(!condition) throw AssertionError("assertion failure") else UnitValue
    }

    define("assertResult") { case List(a: Value) =>
      NativeFunctionValue{
        case List(b: Value) =>
          if(a != b) throw AssertionError(s"expected: ${a}, actual: ${b}") else UnitValue
      }
    }

    define("head") { case List(ObjectValue(list: java.util.List[_])) =>
      Value.toKlassic(list.get(0).asInstanceOf[AnyRef])
    }
    define("tail") { case List(ObjectValue(list: java.util.List[_])) =>
      Value.toKlassic(list.subList(1, list.size()))
    }
    define("cons") { case List(value: Value) =>
      NativeFunctionValue{ case List(ObjectValue(list: java.util.List[_])) =>
        val newList = new java.util.ArrayList[Any]
        var i = 0
        newList.add(Value.fromKlassic(value))
        while(i < list.size()) {
          newList.add(list.get(i))
          i += 1
        }
        Value.toKlassic(newList)
      }
    }
    define("size") { case List(ObjectValue(list: java.util.List[_])) =>
      BoxedInt(list.size())
    }
    define("isEmpty") { case List(ObjectValue(list: java.util.List[_])) =>
      BoxedBoolean(list.isEmpty)
    }
    define("ToDo") { case Nil =>
      throw NotImplementedError("not implemented yet")
    }
    define("url") { case List(ObjectValue(value: String)) =>
      ObjectValue(new java.net.URL(value))
    }
    define("uri") { case List(ObjectValue(value: String)) =>
      ObjectValue(new java.net.URL(value).toURI)
    }
    define("foldLeft") { case List(ObjectValue(list: java.util.List[_])) =>
      NativeFunctionValue{ case List(init: Value) =>
        NativeFunctionValue { case List(fun: FunctionValue) =>
          val env = new Environment(fun.environment)
          var i = 0
          var result: Value = init
          while(i < list.size()) {
            val params: List[TypedAST] = List(ValueNode(result), ValueNode(Value.toKlassic(list.get(i).asInstanceOf[AnyRef])))
            result = performFunctionInternal(fun.value, params, env)
            i += 1
          }
          result
        }
      }
    }
    define("desktop") { case Nil =>
      ObjectValue(java.awt.Desktop.getDesktop())
    }
    defineValue("null")(
      ObjectValue(null)
    )
  }

  object BuiltinRecordEnvironment extends RecordEnvironment() {
    define("Point")(
      "x" -> IntType,
      "y" -> IntType
    )
  }

  object BuiltinModuleEnvironment extends ModuleEnvironment() {
    private final val LIST= "List"
    private final val MAP = "Map"
    private final val SET = "Set"
    enter(LIST) {
      define("head") { case List(ObjectValue(list: java.util.List[_])) =>
        Value.toKlassic(list.get(0).asInstanceOf[AnyRef])
      }
      define("tail") { case List(ObjectValue(list: java.util.List[_])) =>
        Value.toKlassic(list.subList(1, list.size()))
      }
      define("cons") { case List(value: Value) =>
        NativeFunctionValue { case List(ObjectValue(list: java.util.List[_])) =>
          val newList = new java.util.ArrayList[Any]
          var i = 0
          newList.add(Value.fromKlassic(value))
          while (i < list.size()) {
            newList.add(list.get(i))
            i += 1
          }
          Value.toKlassic(newList)
        }
      }
      define("remove") { case List(ObjectValue(self: java.util.List[_])) =>
        NativeFunctionValue{ case List(a: Value) =>
          val newList = new java.util.ArrayList[Any]
          for(v <- self.asScala) {
            newList.add(v)
          }
          newList.remove(Value.fromKlassic(a))
          ObjectValue(newList)
        }
      }
      define("size") { case List(ObjectValue(list: java.util.List[_])) =>
        BoxedInt(list.size())
      }
      define("isEmpty") { case List(ObjectValue(list: java.util.List[_])) =>
        BoxedBoolean(list.isEmpty)
      }
      define("map") { case List(ObjectValue(list: java.util.List[_])) =>
        NativeFunctionValue{
          case List(fun: FunctionValue) =>
            val newList = new java.util.ArrayList[Any]
            val env = new Environment(fun.environment)
            var i = 0
            while(i < list.size()) {
              val param: Value = Value.toKlassic(list.get(i).asInstanceOf[AnyRef])
              val result: Value = performFunctionInternal(fun.value, List(ValueNode(param)), env)
              newList.add(Value.fromKlassic(result))
              i += 1
            }
            ObjectValue(newList)
        }
      }
      define("foldLeft") { case List(ObjectValue(list: java.util.List[_])) =>
        NativeFunctionValue{ case List(init: Value) =>
          NativeFunctionValue { case List(fun: FunctionValue) =>
            val env = new Environment(fun.environment)
            var i = 0
            var result: Value = init
            while(i < list.size()) {
              val params: List[TypedAST] = List(ValueNode(result), ValueNode(Value.toKlassic(list.get(i).asInstanceOf[AnyRef])))
              result = performFunctionInternal(fun.value, params, env)
              i += 1
            }
            result
          }
        }
      }
    }
    enter(MAP) {
      define("add") { case List(ObjectValue(self: java.util.Map[_, _])) =>
        NativeFunctionValue{ case List(a: Value, b: Value) =>
          val newMap = new java.util.HashMap[Any, Any]()
          for((k, v) <- self.asScala) {
            newMap.put(k, v)
          }
          newMap.put(Value.fromKlassic(a), Value.fromKlassic(b))
          ObjectValue(newMap)
        }
      }
      define("containsKey") { case List(ObjectValue(self: java.util.Map[_, _])) =>
        NativeFunctionValue{ case List(k: Value) =>
          BoxedBoolean(self.containsKey(Value.fromKlassic(k)))
        }
      }
      define("containsValue") { case List(ObjectValue(self: java.util.Map[_, _])) =>
        NativeFunctionValue{ case List(v: Value) =>
          BoxedBoolean(self.containsValue(Value.fromKlassic(v)))
        }
      }
      define("get") { case List(ObjectValue(self: java.util.Map[_, _])) =>
        NativeFunctionValue{ case List(k: Value) =>
          Value.toKlassic(self.get(Value.fromKlassic(k)).asInstanceOf[AnyRef])
        }
      }
      define("size") { case List(ObjectValue(self: java.util.Map[_, _])) =>
        BoxedInt(self.size())
      }
      define("isEmpty") { case List(ObjectValue(map: java.util.Map[_, _])) =>
        BoxedBoolean(map.isEmpty)
      }
    }
    enter(SET) {
      define("add") { case List(ObjectValue(self: java.util.Set[_])) =>
        NativeFunctionValue{ case List(a: Value) =>
          val newSet = new java.util.HashSet[Any]()
          for(v <- self.asScala) {
            newSet.add(v)
          }
          newSet.add(Value.fromKlassic(a))
          ObjectValue(newSet)
        }
      }
      define("remove") { case List(ObjectValue(self: java.util.Set[_])) =>
        NativeFunctionValue{ case List(a: Value) =>
          val newSet = new java.util.HashSet[Any]()
          for(v <- self.asScala) {
            newSet.add(v)
          }
          newSet.remove(Value.fromKlassic(a))
          ObjectValue(newSet)
        }
      }
      define("contains") { case List(ObjectValue(self: java.util.Set[_])) =>
        NativeFunctionValue { case List(a: Value) =>
          BoxedBoolean(self.contains(Value.fromKlassic(a)))
        }
      }
      define("size") { case List(ObjectValue(self: java.util.Set[_])) =>
        BoxedInt(self.size())
      }
      define("isEmpty") { case List(ObjectValue(self: java.util.Set[_])) =>
        BoxedBoolean(self.isEmpty)
      }
    }
  }

  def evaluateFile(file: File): Value = using(new BufferedReader(new InputStreamReader(new FileInputStream(file)))){in =>
    val program = Iterator.continually(in.read()).takeWhile(_ != -1).map(_.toChar).mkString
    evaluateString(program)
  }
  def evaluateString(program: String, fileName: String = "<no file>"): Value = {
    val parser = new Parser
    parser.parse(program) match {
      case parser.Success(program: Program, _) =>
        val tv = typer.newTypeVariable()
        val (typedExpression, _) = typer.doType(rewriter.doRewrite(program.block), TypeEnvironment(typer.BuiltinEnvironment, Set.empty, typer.BuiltinRecordEnvironment, typer.BuiltinModuleEnvironment, None), tv, typer.EmptySubstitution)
        evaluate(typedExpression)
      case parser.Failure(m, n) => throw new InterpreterException(n.pos + ":" + m)
      case parser.Error(m, n) => throw new InterpreterException(n.pos + ":" + m)
    }
  }

  def doParse(program: String): Program = {
    val parser = new Parser
    parser.parse(program) match {
      case parser.Success(program: Program, _) => program
      case parser.Failure(m, n) => throw new InterpreterException(n.pos + ":" + m)
      case parser.Error(m, n) => throw new InterpreterException(n.pos + ":" + m)
    }
  }

  private def evaluate(node: TypedAST): Value = evaluate(node, BuiltinEnvironment)
  private def performFunctionInternal(func: TypedAST, params: List[TypedAST], env: Environment): Value = {
    performFunction(TypedAST.FunctionCall(DynamicType, NoLocation, func, params), env)
  }
  private def performFunction(node: TypedAST.FunctionCall, env: Environment): Value = node match {
    case TypedAST.FunctionCall(description, location, func, params) =>
      evaluate(func, env) match {
        case FunctionValue(TypedAST.FunctionLiteral(description, location, fparams, optionalType, proc), cleanup, cenv) =>
          val local = new Environment(cenv)
          (fparams zip params).foreach{ case (fp, ap) =>
            local(fp.name) = evaluate(ap, env)
          }
          try {
            evaluate(proc, local)
          } finally {
            cleanup.foreach { expression =>
              evaluate(expression, local)
            }
          }
        case NativeFunctionValue(body) =>
          val actualParams = params.map{p => evaluate(p, env)}
          if(body.isDefinedAt(actualParams)) {
            body(params.map{p => evaluate(p, env)})
          } else {
            reportError("parameters are not matched to the function's arguments")
          }
        case _ =>
          reportError("unknown error")
      }
  }
  private def evaluate(node: TypedAST, env: Environment, recordEnv: RecordEnvironment = BuiltinRecordEnvironment, moduleEnv: ModuleEnvironment = BuiltinModuleEnvironment): Value = {
    def evalRecursive(node: TypedAST): Value = {
      node match{
        case TypedAST.Block(description, location, expressions) =>
          val local = new Environment(Some(env))
          expressions.foldLeft(UnitValue:Value){(result, x) => evaluate(x, local)}
        case TypedAST.WhileExpression(description, location, cond, body) =>
          while(evalRecursive(cond) == BoxedBoolean(true)) {
            evalRecursive(body)
          }
          UnitValue
        case TypedAST.IfExpression(description, location, condition, pos, neg) =>
          evalRecursive(condition) match {
            case BoxedBoolean(true) => evalRecursive(pos)
            case BoxedBoolean(false) => evalRecursive(neg)
            case _ => reportError("type error")
          }
        case TypedAST.BinaryExpression(description, location, Operator.AND2, lhs, rhs) =>
          evalRecursive(lhs) match {
            case BoxedBoolean(true) => evalRecursive(rhs)
            case BoxedBoolean(false) => BoxedBoolean(false)
            case _ => reportError("type error")
          }
        case TypedAST.BinaryExpression(description, location, Operator.BAR2, lhs, rhs) =>
          evalRecursive(lhs) match {
            case BoxedBoolean(false) => evalRecursive(rhs)
            case BoxedBoolean(true) => BoxedBoolean(true)
            case _ => reportError("type error")
          }
        case TypedAST.BinaryExpression(description, location, Operator.EQUAL, left, right) =>
          (evalRecursive(left), evalRecursive(right)) match {
            case (BoxedInt(lval), BoxedInt(rval)) => BoxedBoolean(lval == rval)
            case (BoxedLong(lval), BoxedLong(rval)) => BoxedBoolean(lval == rval)
            case (BoxedShort(lval), BoxedShort(rval)) => BoxedBoolean(lval == rval)
            case (BoxedByte(lval), BoxedByte(rval)) => BoxedBoolean(lval == rval)
            case (BoxedFloat(lval), BoxedFloat(rval)) => BoxedBoolean(lval == rval)
            case (BoxedDouble(lval), BoxedDouble(rval)) => BoxedBoolean(lval == rval)
            case (BoxedBoolean(lval), BoxedBoolean(rval)) => BoxedBoolean(lval == rval)
            case (BoxedBoolean(lval), ObjectValue(rval:java.lang.Boolean)) => BoxedBoolean(lval == rval.booleanValue())
            case (ObjectValue(lval:java.lang.Boolean), BoxedBoolean(rval)) => BoxedBoolean(lval.booleanValue() == rval)
            case (ObjectValue(lval), ObjectValue(rval)) => BoxedBoolean(lval == rval)
            case _ => reportError("comparation must be done between same types")
          }
        case TypedAST.BinaryExpression(description, location, Operator.LESS_THAN, left, right) =>
          (evalRecursive(left), evalRecursive(right)) match {
            case (BoxedInt(lval), BoxedInt(rval)) => BoxedBoolean(lval < rval)
            case (BoxedLong(lval), BoxedLong(rval)) => BoxedBoolean(lval < rval)
            case (BoxedShort(lval), BoxedShort(rval)) => BoxedBoolean(lval < rval)
            case (BoxedByte(lval), BoxedByte(rval)) => BoxedBoolean(lval < rval)
            case (BoxedFloat(lval), BoxedFloat(rval)) => BoxedBoolean(lval < rval)
            case (BoxedDouble(lval), BoxedDouble(rval)) => BoxedBoolean(lval < rval)
            case _ => reportError("comparation must be done between numeric types")
          }
        case TypedAST.BinaryExpression(description, location, Operator.GREATER_THAN, left, right) =>
          (evalRecursive(left), evalRecursive(right)) match {
            case (BoxedInt(lval), BoxedInt(rval)) => BoxedBoolean(lval > rval)
            case (BoxedLong(lval), BoxedLong(rval)) => BoxedBoolean(lval > rval)
            case (BoxedShort(lval), BoxedShort(rval)) => BoxedBoolean(lval > rval)
            case (BoxedByte(lval), BoxedByte(rval)) => BoxedBoolean(lval > rval)
            case (BoxedFloat(lval), BoxedFloat(rval)) => BoxedBoolean(lval > rval)
            case (BoxedDouble(lval), BoxedDouble(rval)) => BoxedBoolean(lval > rval)
            case _ => reportError("comparation must be done between numeric types")
          }
        case TypedAST.BinaryExpression(description, location, Operator.LESS_OR_EQUAL, left, right) =>
          (evalRecursive(left), evalRecursive(right)) match {
            case (BoxedInt(lval), BoxedInt(rval)) => BoxedBoolean(lval <= rval)
            case (BoxedLong(lval), BoxedLong(rval)) => BoxedBoolean(lval <= rval)
            case (BoxedShort(lval), BoxedShort(rval)) => BoxedBoolean(lval <= rval)
            case (BoxedByte(lval), BoxedByte(rval)) => BoxedBoolean(lval <= rval)
            case (BoxedFloat(lval), BoxedFloat(rval)) => BoxedBoolean(lval <= rval)
            case (BoxedDouble(lval), BoxedDouble(rval)) => BoxedBoolean(lval <= rval)
            case _ => reportError("comparation must be done between numeric types")
          }
        case TypedAST.BinaryExpression(description, location, Operator.GREATER_EQUAL, left, right) =>
          (evalRecursive(left), evalRecursive(right)) match {
            case (BoxedInt(lval), BoxedInt(rval)) => BoxedBoolean(lval >= rval)
            case (BoxedLong(lval), BoxedLong(rval)) => BoxedBoolean(lval >= rval)
            case (BoxedShort(lval), BoxedShort(rval)) => BoxedBoolean(lval >= rval)
            case (BoxedByte(lval), BoxedByte(rval)) => BoxedBoolean(lval >= rval)
            case (BoxedFloat(lval), BoxedFloat(rval)) => BoxedBoolean(lval >= rval)
            case (BoxedDouble(lval), BoxedDouble(rval)) => BoxedBoolean(lval >= rval)
            case _ => reportError("comparation must be done between numeric types")
          }
        case TypedAST.BinaryExpression(description, location, Operator.ADD, left, right) =>
          (evalRecursive(left), evalRecursive(right)) match{
            case (BoxedInt(lval), BoxedInt(rval)) => BoxedInt(lval + rval)
            case (BoxedLong(lval), BoxedLong(rval)) => BoxedLong(lval + rval)
            case (BoxedShort(lval), BoxedShort(rval)) => BoxedShort((lval + rval).toShort)
            case (BoxedByte(lval), BoxedByte(rval)) => BoxedByte((lval + rval).toByte)
            case (ObjectValue(lval:String), rval) => ObjectValue(lval + rval)
            case (lval, ObjectValue(rval:String)) => ObjectValue(lval + rval)
            case (BoxedFloat(lval), BoxedFloat(rval)) => BoxedFloat((lval + rval))
            case (BoxedDouble(lval), BoxedDouble(rval)) => BoxedDouble(lval + rval)
            case _ => reportError("arithmetic operation must be done between the same numeric types")
          }
        case TypedAST.BinaryExpression(description, location, Operator.SUBTRACT, left, right) =>
          (evalRecursive(left), evalRecursive(right)) match{
            case (BoxedInt(lval), BoxedInt(rval)) => BoxedInt(lval - rval)
            case (BoxedLong(lval), BoxedLong(rval)) => BoxedLong(lval - rval)
            case (BoxedShort(lval), BoxedShort(rval)) => BoxedShort((lval - rval).toShort)
            case (BoxedByte(lval), BoxedByte(rval)) => BoxedByte((lval - rval).toByte)
            case (BoxedFloat(lval), BoxedFloat(rval)) => BoxedFloat((lval - rval))
            case (BoxedDouble(lval), BoxedDouble(rval)) => BoxedDouble(lval - rval)
            case _ => reportError("arithmetic operation must be done between the same numeric types")
          }
        case TypedAST.BinaryExpression(description, location, Operator.MULTIPLY, left, right) =>
          (evalRecursive(left), evalRecursive(right)) match{
            case (BoxedInt(lval), BoxedInt(rval)) => BoxedInt(lval * rval)
            case (BoxedLong(lval), BoxedLong(rval)) => BoxedLong(lval * rval)
            case (BoxedShort(lval), BoxedShort(rval)) => BoxedShort((lval * rval).toShort)
            case (BoxedByte(lval), BoxedByte(rval)) => BoxedByte((lval * rval).toByte)
            case (BoxedFloat(lval), BoxedFloat(rval)) => BoxedFloat((lval * rval))
            case (BoxedDouble(lval), BoxedDouble(rval)) => BoxedDouble(lval * rval)
            case _ => reportError("arithmetic operation must be done between the same numeric types")
          }
        case TypedAST.BinaryExpression(description, location, Operator.DIVIDE, left, right) =>
          (evalRecursive(left), evalRecursive(right)) match {
            case (BoxedInt(lval), BoxedInt(rval)) => BoxedInt(lval / rval)
            case (BoxedLong(lval), BoxedLong(rval)) => BoxedLong(lval / rval)
            case (BoxedShort(lval), BoxedShort(rval)) => BoxedShort((lval / rval).toShort)
            case (BoxedByte(lval), BoxedByte(rval)) => BoxedByte((lval / rval).toByte)
            case (BoxedFloat(lval), BoxedFloat(rval)) => BoxedFloat((lval / rval))
            case (BoxedDouble(lval), BoxedDouble(rval)) => BoxedDouble(lval / rval)
            case _ => reportError("arithmetic operation must be done between the same numeric types")
          }
        case TypedAST.MinusOp(description, location, operand) =>
          evalRecursive(operand) match {
            case BoxedInt(value) => BoxedInt(-value)
            case BoxedLong(value) => BoxedLong(-value)
            case BoxedShort(value) => BoxedShort((-value).toShort)
            case BoxedByte(value) => BoxedByte((-value).toByte)
            case BoxedFloat(value) => BoxedFloat(-value)
            case BoxedDouble(value) => BoxedDouble(-value)
            case _ => reportError("- cannot be applied to non-integer value")
          }
        case TypedAST.PlusOp(description, location, operand) =>
          evalRecursive(operand) match {
            case BoxedInt(value) => BoxedInt(value)
            case BoxedLong(value) => BoxedLong(value)
            case BoxedShort(value) => BoxedShort(value)
            case BoxedByte(value) => BoxedByte(value)
            case BoxedFloat(value) => BoxedFloat(value)
            case BoxedDouble(value) => BoxedDouble(value)
            case _ => reportError("+ cannot be applied to non-integer value")
          }
        case TypedAST.IntNode(description, location, value) =>
          BoxedInt(value)
        case TypedAST.StringNode(description, location, value) =>
          ObjectValue(value)
        case TypedAST.LongNode(description, location, value) =>
          BoxedLong(value)
        case TypedAST.ShortNode(description, location, value) =>
          BoxedShort(value)
        case TypedAST.ByteNode(description, location, value) =>
          BoxedByte(value)
        case TypedAST.DoubleNode(description, location, value) =>
          BoxedDouble(value)
        case TypedAST.FloatNode(description, location, value) =>
          BoxedFloat(value)
        case TypedAST.BooleanNode(description, location, value) =>
          BoxedBoolean(value)
        case TypedAST.ListLiteral(description, location, elements) =>
          val params = elements.map{e => Value.fromKlassic(evalRecursive(e))}
          val newList = new java.util.ArrayList[Any]
          params.foreach{param =>
            newList.add(param)
          }
          ObjectValue(newList)
        case TypedAST.SetLiteral(description, location, elements) =>
          val params = elements.map{e => Value.fromKlassic(evalRecursive(e))}
          val newSet = new java.util.HashSet[Any]
          params.foreach{param =>
            newSet.add(param)
          }
          ObjectValue(newSet)
        case TypedAST.MapLiteral(description, location, elements) =>
          val params = elements.map{ case (k, v) =>
            (Value.fromKlassic(evalRecursive(k)), Value.fromKlassic(evalRecursive(v)))
          }
          val newMap = new java.util.HashMap[Any, Any]
          params.foreach{ case (k, v) =>
            newMap.put(k, v)
          }
          ObjectValue(newMap)
        case TypedAST.Id(description, location, name) =>
          env(name)
        case TypedAST.Selector(description, location, module, name) =>
          moduleEnv(module)(name)
        case TypedAST.LetDeclaration(description, location, vr, optVariableType, value, body, immutable) =>
          env(vr) = evalRecursive(value)
          evalRecursive(body)
        case TypedAST.Assignment(description, location, vr, value) =>
          env.set(vr, evalRecursive(value))
        case literal@TypedAST.FunctionLiteral(description, location, _, _, _) =>
          FunctionValue(literal, None, Some(env))
        case TypedAST.LetFunctionDefinition(description, location, name, body, cleanup, expression) =>
          env(name) = FunctionValue(body, cleanup, Some(env)): Value
          evalRecursive(expression)
        case TypedAST.MethodCall(description, location, self, name, params) =>
          evalRecursive(self) match {
            case ObjectValue(value) =>
              val paramsArray = params.map{p => evalRecursive(p)}.toArray
              findMethod(value, name, paramsArray) match {
                case UnboxedVersionMethodFound(method) =>
                  val actualParams = paramsArray.map{Value.fromKlassic}
                  Value.toKlassic(method.invoke(value, actualParams:_*))
                case BoxedVersionMethodFound(method) =>
                  val actualParams = paramsArray.map{Value.fromKlassic}
                  Value.toKlassic(method.invoke(value, actualParams:_*))
                case NoMethodFound =>
                  throw new IllegalArgumentException(s"${self}.${name}(${params})")
              }
            case otherwise =>
              sys.error(s"cannot reach here: ${otherwise}")
          }
        case TypedAST.NewObject(description, location, className, params) =>
          val paramsArray = params.map{evalRecursive}.toArray
          findConstructor(Class.forName(className), paramsArray) match {
            case UnboxedVersionConstructorFound(constructor) =>
              val actualParams = paramsArray.map{Value.fromKlassic}
              Value.toKlassic(constructor.newInstance(actualParams:_*).asInstanceOf[AnyRef])
            case BoxedVersionConstructorFound(constructor) =>
              val actualParams = paramsArray.map{Value.fromKlassic}
              Value.toKlassic(constructor.newInstance(actualParams:_*).asInstanceOf[AnyRef])
            case NoConstructorFound =>
              throw new IllegalArgumentException(s"new ${className}(${params}) is not found")
          }
        case TypedAST.NewRecord(description, location, recordName, params) =>
          val paramsList = params.map{evalRecursive}
          recordEnv.records.get(recordName) match {
            case None => throw new IllegalArgumentException(s"record ${recordName} is not found")
            case Some(argsList) =>
              val members = (argsList zip paramsList).map{ case ((n, _), v) => n -> v }
              RecordValue(recordName, members)
          }
        case TypedAST.AccessRecord(description, location, expression, memberName) =>
          evalRecursive(expression) match {
            case RecordValue(recordName, members) =>
              members.find{ case (mname, mtype) => memberName == mname} match {
                case None =>
                  throw new IllegalArgumentException(s"member ${memberName} is not found in record ${recordName}")
                case Some((_, value)) =>
                  value
              }
            case v =>
              throw new IllegalArgumentException(s"value ${v} is not record")
          }
        case call@TypedAST.FunctionCall(description, location, func, params) =>
          performFunction(call, env)
        case TypedAST.Casting(description, location, target, to) =>
          evalRecursive(target)
        case TypedAST.ValueNode(value) =>
          value
        case otherwise@TypedAST.ForeachExpression(description, location, _, _, _) => sys.error(s"cannot reach here: ${otherwise}")
      }
    }
    evalRecursive(node)
  }
}
