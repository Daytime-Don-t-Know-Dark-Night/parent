package boluo.basic

object S03_Circulate {

    def main(args: Array[String]): Unit = {

        // 1. while
        var a = 10
        while (a < 20) {
            println("Value of a: " + a)
            a += 1
        }

        // 2. do...while
        var b = 10
        do {
            println("Value of b: " + b)
            b += 1
        } while (b < 20)

        // 3. for
        // i to j (包含j)
        var c = 0
        for (c <- 1 to 10) {
            println("value of c: " + c)
        }

        // i until j (不包含j)
        var d = 0
        for (d <- 1 until 10) {
            println("value of d: " + d)
        }

        // TODO https://www.runoob.com/scala/scala-for-loop.html
        // 在for循环中可以使用分号(;)来设置多个区间, 它将迭代给定区间所有的可能值
        var e = 0
        var f = 0
        for (e <- 1 to 3; f <- 1 to 3) {
            println("value of e: " + e)
            println("value of f: " + f)
        }

        // for 循环集合
        for (x <- List(1, 2, 3)) {
            println(x)
        }

        // for 循环过滤
        for (x <- List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
             if x != 3; if x < 8) {
            println(x)
        }

        // for 使用yield
        // 可以将for循环的返回值作为一个变量存储
        var retVal = for {x <- List(1, 2, 3, 4, 5, 6, 7, 8, 9)
                          if x != 3; if x < 8
                          } yield x
        // 输出返回值
        for (a <- retVal) {
            println("Value of a: " + a)
        }

    }
}
